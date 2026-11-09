package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpBrowserConnection
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * A start that fails or is cancelled while it waits for Chrome's first answer leaves nothing
 * behind.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent starts the launch on the virtual clock.
@Suppress("InjectDispatcher") // A real process and real sockets need a real IO dispatcher.
class ChromeStartTest {
    @Test
    fun `cancelled launch stops Chrome`(@TempDir dir: File) = runTest {
        val started = File(dir, "started")
        assertEquals(0, ProcessBuilder("mkfifo", started.path).start().waitFor())
        // Reads the first byte of Browser.getVersion, says its pid, and never answers.
        val chrome =
            File(dir, "chrome").apply {
                writeText(
                    listOf(
                            "#!/bin/sh",
                            "head -c 1 <&3 >/dev/null",
                            "echo $$ > '${started.path}'",
                            "exec sleep 600",
                        )
                        .joinToString("\n")
                )
                setExecutable(true)
            }
        val launcher =
            ChromeLauncher(
                BrowserBinary(chrome, chromium = false),
                profile = File(dir, "profile"),
                headless = true,
                extraArgs = emptyList(),
                logFile = File(dir, "chrome.log"),
                io = Dispatchers.IO,
            )

        val launch =
            launch(Dispatchers.IO) {
                try {
                    launcher.start().stop()
                } catch (e: LaunchException) {
                    // Opening the FIFO frees the reader below, which then fails on pid 0.
                    started.writeText("0")
                    throw e
                }
            }
        val pid = runInterruptible(Dispatchers.IO) { started.readText().trim().toLong() }
        val process = ProcessHandle.of(pid).orElseThrow()
        try {
            launch.cancelAndJoin()
            // Waits for the exit itself. The limit only ends a run where the process was left.
            runInterruptible(Dispatchers.IO) {
                process.onExit().completeOnTimeout(process, EXIT_LIMIT_S, TimeUnit.SECONDS).join()
            }
            assertFalse(process.isAlive, "Chrome kept running after its launch was cancelled")
        } finally {
            process.destroyForcibly()
        }
    }

    /**
     * A Chrome that never answers and ignores SIGTERM, as Chrome for Testing 125 does while its
     * main thread waits on a macOS keychain prompt. The launch gives up on it and kills it, so it
     * doesn't hold the profile. The launch runs on the virtual clock, so its DevTools timeout and
     * its wait for the exit pass at once.
     */
    @Test
    fun `stuck Chrome is killed`(@TempDir dir: File) = runTest {
        val started = File(dir, "started")
        assertEquals(0, ProcessBuilder("mkfifo", started.path).start().waitFor())
        val chrome =
            File(dir, "chrome").apply {
                writeText(
                    listOf(
                            "#!/bin/sh",
                            "trap '' TERM",
                            "echo $$ > '${started.path}'",
                            "exec sleep 600",
                        )
                        .joinToString("\n")
                )
                setExecutable(true)
            }
        val launcher =
            ChromeLauncher(
                BrowserBinary(chrome, chromium = false),
                profile = File(dir, "profile"),
                headless = true,
                extraArgs = emptyList(),
                logFile = File(dir, "chrome.log"),
                io = Dispatchers.IO,
            )
        val launch = async { assertThrows<LaunchException> { launcher.start().stop() } }
        runCurrent()
        // Read on the test's thread, so the virtual clock waits until the fake ignores SIGTERM.
        val pid = started.readText().trim().toLong()
        val process = ProcessHandle.of(pid).orElseThrow()
        try {
            launch.await()
            // Waits for the exit itself. The limit only ends a run where the process was left.
            runInterruptible(Dispatchers.IO) {
                process.onExit().completeOnTimeout(process, EXIT_LIMIT_S, TimeUnit.SECONDS).join()
            }
            assertFalse(process.isAlive, "Chrome kept running after its launch failed")
        } finally {
            process.destroyForcibly()
        }
    }

    /**
     * The first window opens with `background`, since `Target.createTarget` without it brings
     * Chrome in front of whichever app the person is using.
     */
    @Test
    fun `first window opens hidden`() = runTest {
        FakeChrome().use { chrome ->
            CdpBrowserConnection(chrome, StandardTestDispatcher(testScheduler)).use { connection ->
                connection.start()
                openFirstWindow(connection)
                val (method, params) = chrome.commands.single()
                assertEquals("Target.createTarget", method)
                assertEquals(true, params["newWindow"]?.jsonPrimitive?.boolean)
                assertEquals(true, params["background"]?.jsonPrimitive?.boolean)
            }
        }
    }

    @Test
    fun `cancelled attach closes socket`() = runTest {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val asked = CompletableDeferred<Unit>()
            val accepted = ConcurrentLinkedQueue<Socket>()
            val devtools = async(Dispatchers.IO) { fakeDevtools(server, accepted, asked) }
            val attach =
                launch(Dispatchers.IO) {
                    try {
                        ChromeAttacher("${server.localPort}", Dispatchers.IO).start().stop()
                    } catch (e: LaunchException) {
                        asked.completeExceptionally(e)
                    }
                }
            try {
                asked.await()
                attach.cancelAndJoin()
                // Waits for the close itself. The limit only ends a run where the socket was left.
                val closed =
                    withContext(Dispatchers.IO) {
                        withTimeoutOrNull(CLOSE_LIMIT_MS) { devtools.await() }
                            ?: run {
                                accepted.forEach(Socket::close)
                                devtools.await()
                            }
                    }
                assertTrue(closed, "The attach left its socket open after it was cancelled")
            } finally {
                // Frees the fake's blocked read when the attach left its socket open.
                accepted.forEach(Socket::close)
            }
        }
    }

    /**
     * Answers `/json/version` and the WebSocket upgrade, takes the first byte of
     * Browser.getVersion, completes [asked], and never answers. True once the client closes its
     * socket, false when the test closes it instead.
     */
    private fun fakeDevtools(
        server: ServerSocket,
        accepted: MutableCollection<Socket>,
        asked: CompletableDeferred<Unit>,
    ): Boolean {
        server.accept().also(accepted::add).use { http ->
            http.getInputStream().readHeaders()
            val url = "ws://127.0.0.1:${server.localPort}/devtools/browser/x"
            val body = """{"webSocketDebuggerUrl":"$url"}"""
            http
                .getOutputStream()
                .write(response("200 OK", listOf("Content-Length: ${body.length}"), body))
        }
        server.accept().also(accepted::add).use { ws ->
            val input = ws.getInputStream()
            val key =
                input.readHeaders().firstNotNullOf { line ->
                    line
                        .takeIf { it.startsWith("sec-websocket-key:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                }
            val headers =
                listOf(
                    "Upgrade: websocket",
                    "Connection: Upgrade",
                    "Sec-WebSocket-Accept: ${accept(key)}",
                )
            ws.getOutputStream().write(response("101 Switching Protocols", headers))
            input.read()
            asked.complete(Unit)
            return try {
                input.readAllBytes()
                true
            } catch (_: SocketException) {
                false
            }
        }
    }

    /** The status line, [headers], a blank line, and [body]. */
    private fun response(status: String, headers: List<String>, body: String = ""): ByteArray =
        (listOf("HTTP/1.1 $status") + headers + "")
            .joinToString(CRLF, postfix = CRLF + body)
            .toByteArray()

    private fun InputStream.readHeaders(): List<String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        while (true) {
            val b = read()
            check(b >= 0) { "The request ended early" }
            if (b == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) return lines
                lines += text
                line.clear()
            } else {
                line.append(b.toChar())
            }
        }
    }

    private fun accept(key: String): String =
        Base64.getEncoder()
            .encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray())
            )

    private companion object {
        const val EXIT_LIMIT_S = 10L
        const val CLOSE_LIMIT_MS = 10_000L
        const val CRLF = "\r\n"
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
