package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpBrowserConnection
import com.fingertipsmcp.browser.ChromeSocket
import com.fingertipsmcp.browser.PipeTransport
import com.fingertipsmcp.browser.WebSocketTransport
import com.fingertipsmcp.browser.decodeCdp
import com.fingertipsmcp.browser.httpGet
import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.ApiException
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory

/**
 * A browser fingertips-chrome is connected to. Closing it closes the browser if fingertips-chrome
 * started it.
 */
interface RunningBrowser : Closeable {
    val connection: CdpBrowserConnection

    /** Chrome's process id, when fingertips-chrome started it. */
    val pid: Long?

    /** `Browser.getVersion`'s product, such as `Chrome/141.0.7390.54`. */
    val product: String
}

/**
 * Starts Chrome with `--remote-debugging-pipe`: DevTools on file descriptors 3 and 4 instead of a
 * port, so nothing else on the machine can connect and there is no listening socket to find. The
 * profile persists in [profile]. Flags are kept to what a person's Chrome would not visibly lack:
 * no `--enable-automation`, `--no-sandbox`, or `--disable-gpu`.
 *
 * Any remote-debugging switch, the pipe included, makes Chrome report `navigator.webdriver` true,
 * as an uncontrolled Chrome never does. [hideAutomation] turns that off with
 * `--disable-blink-features=AutomationControlled`, the one Blink switch fingertips-chrome passes.
 * Chrome answers that switch with an "unsupported command-line flag" bar on the tab it opens at
 * launch, so a windowed Chrome starts with `--no-startup-window` and fingertips-chrome opens the
 * first window itself. That also means Chrome restores no session at launch.
 */
class ChromeLauncher(
    private val binary: BrowserBinary,
    private val profile: File,
    private val headless: Boolean,
    private val extraArgs: List<String>,
    private val logFile: File,
    private val io: CoroutineContext,
    private val hideAutomation: Boolean = true,
) {
    fun arguments(): List<String> = buildList {
        add("--remote-debugging-pipe")
        add("--user-data-dir=${profile.absolutePath}")
        // A fresh profile otherwise opens the welcome and default-browser prompts over every tab.
        add("--no-first-run")
        add("--no-default-browser-check")
        if (headless) add("--headless=new")
        if (hideAutomation) add("--disable-blink-features=AutomationControlled")
        // No launch tab to carry the flag's warning bar, and no restored session. Headless Chrome
        // has no bar to show, and opens its own first tab.
        if (!headless) add("--no-startup-window")
        addAll(extraArgs)
    }

    suspend fun start(): RunningBrowser {
        profile.mkdirs()
        logFile.parentFile?.mkdirs()
        val logStart = if (logFile.exists()) logFile.length() else 0L
        val process =
            withContext(io) {
                try {
                    ProcessBuilder(
                            listOf(
                                "/bin/sh",
                                "-c",
                                PIPE_SHIM,
                                "fingertips-chrome-chrome",
                                binary.path.path,
                            ) + arguments()
                        )
                        .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
                        .start()
                } catch (e: IOException) {
                    throw LaunchException("Could not run ${binary.path}: ${e.message.orEmpty()}", e)
                }
            }
        val connection =
            CdpBrowserConnection(PipeTransport(process.inputStream, process.outputStream, io), io)
        connection.start()
        val product =
            try {
                browserVersion(connection)
            } catch (e: ApiException) {
                connection.close()
                process.destroy()
                throw LaunchException(
                    "Chrome did not start (${e.message.orEmpty()}). If another Chrome uses ${profile.path}, " +
                        "close it or pass --profile. ${logTail(logFile, logStart)}",
                    e,
                )
            }
        logger.info("Started {} (pid {}) with profile {}", product, process.pid(), profile)
        if (!headless) {
            try {
                openFirstWindow(connection)
            } catch (e: ApiException) {
                connection.close()
                process.destroy()
                throw LaunchException(
                    "Chrome started but did not open a window: ${e.message.orEmpty()}",
                    e,
                )
            }
        }
        return LaunchedChrome(connection, process, product)
    }

    /** The window a person would see when Chrome opens: one blank tab. */
    private suspend fun openFirstWindow(connection: CdpBrowserConnection) {
        connection.root.call(
            "Target.createTarget",
            buildJsonObject {
                put("url", "about:blank")
                put("newWindow", true)
            },
        )
    }

    private class LaunchedChrome(
        override val connection: CdpBrowserConnection,
        private val process: Process,
        override val product: String,
    ) : RunningBrowser {
        override val pid: Long = process.pid()

        override fun close() {
            if (process.isAlive && connection.isOpen) {
                runBlocking {
                    withTimeoutOrNull(CLOSE_MS) {
                        try {
                            connection.root.call("Browser.close")
                        } catch (_: ApiException) {
                            // Closing the pipe below ends it anyway.
                        }
                    }
                }
            }
            // Chrome also exits when the pipe closes.
            connection.close()
            if (!process.waitFor(EXIT_WAIT_S, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(EXIT_WAIT_S, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }

    private companion object {
        /**
         * Java starts a child with only stdin, stdout, and stderr. The shell turns those into
         * Chrome's pipe: stdin becomes fd 3, which Chrome reads commands from, and stdout becomes
         * fd 4, which it writes to. Chrome's own output goes to stderr, which is the log file.
         */
        const val PIPE_SHIM = "exec 3<&0 4>&1 0</dev/null 1>&2; exec \"$@\""
        const val CLOSE_MS = 3_000L
        const val EXIT_WAIT_S = 5L
    }
}

/**
 * Development only: a Chrome already started with `--remote-debugging-port`. [target] is a port,
 * `host:port`, an `http://` URL, or the browser's `ws://` URL. The port is open to every local
 * process, which is why the normal path is the pipe.
 */
class ChromeAttacher(private val target: String, private val io: CoroutineContext) {
    @Suppress("MissingUseCall") // The returned browser owns the socket and closes it.
    suspend fun start(): RunningBrowser =
        withContext(io) {
            val (host, port, path) = endpoint()
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_MS)
                val chrome = ChromeSocket(socket.getInputStream(), socket.getOutputStream(), io)
                chrome.handshake(path)
                chrome.connect()
                val connection =
                    CdpBrowserConnection(WebSocketTransport(chrome) { socket.close() }, io)
                connection.start()
                val product = browserVersion(connection)
                logger.warn(
                    "Attached to {} on {}:{}. Use this for development only.",
                    product,
                    host,
                    port,
                )
                AttachedChrome(connection, product)
            } catch (e: IOException) {
                socket.close()
                throw LaunchException(
                    "Could not attach to Chrome at $target: ${e.message.orEmpty()}",
                    e,
                )
            } catch (e: ApiException) {
                socket.close()
                throw LaunchException(
                    "Could not attach to Chrome at $target: ${e.message.orEmpty()}",
                    e,
                )
            }
        }

    private fun endpoint(): Triple<String, Int, String> {
        if (target.startsWith("ws://")) {
            val uri = URI(target)
            return Triple(uri.host, uri.port, uri.rawPath)
        }
        val base =
            when {
                target.all(Char::isDigit) -> URI("http://127.0.0.1:$target")
                target.startsWith("http://") -> URI(target)
                else -> URI("http://$target")
            }
        val host = base.host ?: throw LaunchException("No host in --attach $target")
        val port =
            base.port.takeIf { it > 0 } ?: throw LaunchException("No port in --attach $target")
        val version =
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_MS)
                httpGet(
                    socket.getInputStream(),
                    socket.getOutputStream(),
                    "/json/version",
                    MAX_VERSION_BODY,
                )
            }
        val url =
            ApiJson.decodeFromString(serializer<VersionInfo>(), version).webSocketDebuggerUrl
                ?: throw LaunchException("Chrome at $target did not give a browser WebSocket URL")
        return Triple(host, port, URI(url).rawPath)
    }

    private class AttachedChrome(
        override val connection: CdpBrowserConnection,
        override val product: String,
    ) : RunningBrowser {
        override val pid: Long? = null

        override fun close() = connection.close()
    }

    @Serializable private data class VersionInfo(val webSocketDebuggerUrl: String? = null)

    private companion object {
        const val CONNECT_MS = 5_000
        const val MAX_VERSION_BODY = 64 * 1024
    }
}

/**
 * Keeps one browser running. The first call starts it; when it has exited, as when its last window
 * is closed, the next call starts it again.
 */
class BrowserSupervisor(private val start: suspend () -> RunningBrowser) : Closeable {
    private val gate = Mutex()
    @Volatile private var running: RunningBrowser? = null

    /** The browser if it is up, without starting it. */
    val current: RunningBrowser?
        get() = running?.takeIf { it.connection.isOpen }

    @Suppress("MissingUseCall") // The supervisor owns the browser and closes it.
    suspend fun connection(): CdpBrowserConnection = browser().connection

    suspend fun browser(): RunningBrowser = gate.withLock {
        val live = running
        if (live != null && live.connection.isOpen) return@withLock live
        live?.let {
            logger.info("The browser exited; starting it again")
            runCatching { it.close() }
        }
        val started =
            try {
                start()
            } catch (e: LaunchException) {
                throw ApiException(503, "browser_unavailable", e.message.orEmpty(), cause = e)
            }
        running = started
        started
    }

    override fun close() {
        running?.close()
        running = null
    }
}

private suspend fun browserVersion(connection: CdpBrowserConnection): String =
    connection.root.call("Browser.getVersion").decodeCdp<BrowserVersion>()?.product ?: "Chrome"

@Serializable private data class BrowserVersion(val product: String? = null)

private fun logTail(log: File, from: Long): String {
    if (!log.exists() || log.length() <= from) return "Chrome wrote nothing to ${log.path}."
    val text =
        RandomAccessFile(log, "r").use { file ->
            val start = maxOf(from, file.length() - LOG_TAIL_BYTES)
            file.seek(start)
            val bytes = ByteArray((file.length() - start).toInt())
            file.readFully(bytes)
            bytes.decodeToString()
        }
    return "Chrome's log (${log.path}) ends: ${text.trim().lines().takeLast(LOG_TAIL_LINES).joinToString(" | ")}"
}

private const val LOG_TAIL_BYTES = 4_096L
private const val LOG_TAIL_LINES = 6

private val logger = LoggerFactory.getLogger("FingertipsChrome.Launch")
