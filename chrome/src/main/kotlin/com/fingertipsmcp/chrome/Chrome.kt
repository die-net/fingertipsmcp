package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpBrowserConnection
import com.fingertipsmcp.browser.ChromeSocket
import com.fingertipsmcp.browser.PipeTransport
import com.fingertipsmcp.browser.WebSocketTransport
import com.fingertipsmcp.browser.decodeCdp
import com.fingertipsmcp.browser.httpGet
import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.core.closeOnFailure
import com.fingertipsmcp.core.openOn
import com.fingertipsmcp.model.ApiException
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory

/**
 * A browser fingertips-chrome is connected to. Stopping it closes the browser if fingertips-chrome
 * started it.
 */
interface RunningBrowser : Closeable {
    val connection: CdpBrowserConnection

    /** Chrome's process id, when fingertips-chrome started it. */
    val pid: Long?

    /** `Browser.getVersion`'s product, such as `Chrome/141.0.7390.54`. */
    val product: String

    /**
     * Disconnects, and closes Chrome if fingertips-chrome started it, waiting for it to exit. It
     * suspends rather than blocking a thread.
     */
    suspend fun stop()

    /** [stop] for a thread that may block and isn't running coroutines, as a shutdown hook. */
    override fun close() {
        runBlocking { stop() }
    }
}

/**
 * Starts Chrome with `--remote-debugging-pipe`: DevTools on file descriptors 3 and 4 instead of a
 * port, or on Windows on two pipe handles ([WindowsChromeProcess]), so nothing else on the machine
 * can connect and there is no listening socket to find. The profile persists in [profile]. Flags
 * are kept to what a person's Chrome would not visibly lack: no `--enable-automation`,
 * `--no-sandbox`, or `--disable-gpu`.
 *
 * Any remote-debugging switch, the pipe included, makes Chrome report `navigator.webdriver` true,
 * as an uncontrolled Chrome never does. [hideAutomation] turns that off with
 * `--disable-blink-features=AutomationControlled`, the one Blink switch fingertips-chrome passes.
 * Chrome answers that switch with an "unsupported command-line flag" bar on the tab it opens at
 * launch, so a windowed Chrome starts with `--no-startup-window` and fingertips-chrome opens the
 * first window itself. That also means Chrome restores no session at launch. Neither the start nor
 * that window brings Chrome in front of the person's other apps ([openFirstWindow]). A windowed
 * Chrome also gets `--disable-backgrounding-occluded-windows`, so a window covered by another app
 * keeps drawing and taking input without being raised over it. A headless Chrome with automation
 * hidden gets a desktop's screen and a windowed Chrome's user agent ([passesForWindowed]). Chrome
 * sizes and places its windows on that screen as a windowed Chrome does on a new profile.
 */
class ChromeLauncher(
    private val binary: BrowserBinary,
    private val profile: File,
    private val headless: Boolean,
    private val extraArgs: List<String>,
    private val logFile: File,
    private val io: CoroutineContext,
    private val hideAutomation: Boolean = true,
    private val windows: Boolean = isWindows(),
    private val desktopBar: DesktopBar = DesktopBar.forOs(),
) {
    /**
     * Whether a headless Chrome is made to look windowed: the screen a desktop has rather than
     * headless Chrome's 800 by 600 screen, and a user agent without `HeadlessChrome`. Chrome then
     * sizes and places each window on that screen as a windowed Chrome does on a new profile, so
     * there is no `--window-size`. Only when automation is hidden too, since a page that sees
     * `navigator.webdriver` true has nothing left to learn from the rest.
     */
    private val passesForWindowed = headless && hideAutomation

    /**
     * The user agent a windowed Chrome of this release would send, once a start has read it from
     * headless Chrome, or null until then and when the `--chrome-arg` flags set one. Chrome only
     * takes it as a flag, so learning it takes one start without it.
     */
    @Volatile private var userAgent: String? = null

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
        // A tab whose window is wholly behind another app's otherwise reads hidden, draws nothing,
        // and drops input until its window is raised over the app the person is using, which
        // Windows refuses. With this it draws and takes input where it is.
        if (!headless) add("--disable-backgrounding-occluded-windows")
        if (passesForWindowed) addAll(windowedFlags())
        addAll(extraArgs)
    }

    /**
     * The screen and user agent flags that [passesForWindowed] adds, less those [extraArgs] has.
     */
    private fun windowedFlags(): List<String> = buildList {
        if (!extraArgs.hasSwitch("--screen-info")) {
            val workArea =
                listOfNotNull(
                    " workAreaTop=${desktopBar.top}".takeIf { desktopBar.top > 0 },
                    " workAreaBottom=${desktopBar.bottom}".takeIf { desktopBar.bottom > 0 },
                )
            add("--screen-info={${HEADLESS_WIDTH}x$HEADLESS_HEIGHT${workArea.joinToString("")}}")
        }
        userAgent?.let { add("--user-agent=$it") }
    }

    /**
     * Starts Chrome and waits for it to answer, as [startOnce] does. A headless Chrome made to look
     * windowed that reports a user agent other than the one it should is stopped and started again
     * with that one: on the first start, to drop `HeadlessChrome`, and after Chrome is updated, to
     * change the version.
     */
    suspend fun start(): RunningBrowser {
        val (chrome, reported) = startOnce()
        val wanted =
            reported
                ?.takeIf { passesForWindowed && !extraArgs.hasSwitch("--user-agent") }
                ?.let { windowedUserAgent(it, chrome.product) } ?: return chrome
        withContext(NonCancellable) { chrome.stop() }
        userAgent = wanted
        logger.info("Restarting headless Chrome with the user agent a windowed Chrome sends")
        return startOnce().first
    }

    /**
     * Starts Chrome and waits for it to answer. It gives the user agent Chrome reports with it. On
     * any failure, a cancel included, it stops the Chrome it started and waits for it to exit, so a
     * later launch doesn't find the profile taken. A Chrome that never answers may be stuck where
     * it can't take SIGTERM either, as on a macOS keychain prompt, so it is killed when it doesn't
     * exit.
     */
    @Suppress("MissingUseCall") // The launch is stopped unless it is returned.
    private suspend fun startOnce(): Pair<RunningBrowser, String?> {
        profile.mkdirs()
        logFile.parentFile?.mkdirs()
        val logStart = if (logFile.exists()) logFile.length() else 0L
        val launch = openOn(io) { Launch(startProcess()) }
        var started: RunningBrowser? = null
        try {
            val cdp = launch.connect()
            cdp.start()
            val version =
                try {
                    browserVersion(cdp)
                } catch (e: ApiException) {
                    throw LaunchException(
                        "Chrome did not start (${e.message.orEmpty()}). If another Chrome uses " +
                            "${profile.path}, close it or pass --profile. " +
                            logTail(logFile, logStart),
                        e,
                    )
                }
            logger.info(
                "Started {} (pid {}) with profile {}",
                version.product,
                launch.process.pid(),
                profile,
            )
            if (!headless) {
                try {
                    openFirstWindow(cdp)
                } catch (e: ApiException) {
                    throw LaunchException(
                        "Chrome started but did not open a window: ${e.message.orEmpty()}",
                        e,
                    )
                }
            }
            val chrome = LaunchedChrome(cdp, launch.process, version.product).also { started = it }
            return chrome to version.userAgent
        } finally {
            if (started == null) withContext(NonCancellable) { launch.stop() }
        }
    }

    /**
     * A Chrome being started, and its connection once there is one. [stop] stops both. Closing it
     * is only for [openOn], when a cancel comes as the process starts, before Chrome has anything
     * to save, so it kills the process.
     */
    private inner class Launch(val process: Process) : Closeable {
        private var connection: CdpBrowserConnection? = null

        @Suppress("MissingUseCall") // Stopping the launch closes the connection.
        fun connect(): CdpBrowserConnection =
            CdpBrowserConnection(PipeTransport(process.inputStream, process.outputStream, io), io)
                .also { connection = it }

        /** Closes the connection and stops Chrome. */
        suspend fun stop() {
            connection?.close()
            process.terminate()
        }

        override fun close() {
            process.destroyForcibly()
        }
    }

    private fun startProcess(): Process =
        try {
            if (windows) {
                WindowsChromeProcess.start(binary.path, arguments(), logFile)
            } else {
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
            }
        } catch (e: IOException) {
            throw LaunchException("Could not run ${binary.path}: ${e.message.orEmpty()}", e)
        }

    private class LaunchedChrome(
        override val connection: CdpBrowserConnection,
        private val process: Process,
        override val product: String,
    ) : RunningBrowser {
        override val pid: Long = process.pid()

        override suspend fun stop() = stopChrome(process, connection)
    }

    private companion object {
        /**
         * Java starts a child with only stdin, stdout, and stderr. The shell turns those into
         * Chrome's pipe: stdin becomes fd 3, which Chrome reads commands from, and stdout becomes
         * fd 4, which it writes to. Chrome's own output goes to stderr, which is the log file.
         */
        const val PIPE_SHIM = "exec 3<&0 4>&1 0</dev/null 1>&2; exec \"$@\""
    }
}

/**
 * Opens the window a person would see when Chrome opens: one blank tab. It opens with `background`,
 * which keeps Chrome behind whichever app is in front, where without it Chrome comes in front. The
 * window still shows on screen, unfocused, and its tab reads `visible` and draws as any tab in a
 * window that is not in front does.
 */
internal suspend fun openFirstWindow(connection: CdpBrowserConnection) {
    connection.root.call(
        "Target.createTarget",
        buildJsonObject {
            put("url", "about:blank")
            put("newWindow", true)
            put("background", true)
        },
    )
}

/**
 * Development only: a Chrome already started with `--remote-debugging-port`. [target] is a port,
 * `host:port`, an `http://` URL, or the browser's `ws://` URL. The port is open to every local
 * process, which is why the normal path is the pipe.
 */
class ChromeAttacher(private val target: String, private val io: CoroutineContext) {
    /** Connects to Chrome. On any failure, a cancel included, it closes what it opened. */
    @Suppress("MissingUseCall") // closeOnFailure and openOn close what is not returned.
    suspend fun start(): RunningBrowser =
        try {
            openOn(io) {
                val (host, port, path) = endpoint()
                val cdp =
                    Socket().closeOnFailure { socket ->
                        socket.connect(InetSocketAddress(host, port), CONNECT_MS)
                        val chrome =
                            ChromeSocket(socket.getInputStream(), socket.getOutputStream(), io)
                        chrome.handshake(path)
                        chrome.connect()
                        CdpBrowserConnection(WebSocketTransport(chrome) { socket.close() }, io)
                    }
                cdp.closeOnFailure {
                    cdp.start()
                    val product = browserVersion(cdp).product
                    logger.warn(
                        "Attached to {} on {}:{}. Use this for development only.",
                        product,
                        host,
                        port,
                    )
                    AttachedChrome(cdp, product)
                }
            }
        } catch (e: IOException) {
            throw LaunchException(
                "Could not attach to Chrome at $target: ${e.message.orEmpty()}",
                e,
            )
        } catch (e: ApiException) {
            throw LaunchException(
                "Could not attach to Chrome at $target: ${e.message.orEmpty()}",
                e,
            )
        }

    private fun endpoint(): Triple<String, Int, String> {
        if (target.startsWith("ws://")) {
            val uri = uri(target, "--attach $target")
            return Triple(hostOf(uri), portOf(uri), uri.rawPath)
        }
        val base =
            uri(
                when {
                    target.all(Char::isDigit) -> "http://127.0.0.1:$target"
                    target.startsWith("http://") -> target
                    else -> "http://$target"
                },
                "--attach $target",
            )
        val host = hostOf(base)
        val port = portOf(base)
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
            try {
                ApiJson.decodeFromString(serializer<VersionInfo>(), version).webSocketDebuggerUrl
            } catch (e: SerializationException) {
                throw LaunchException("Chrome at $target did not answer /json/version in JSON", e)
            } catch (e: IllegalArgumentException) {
                throw LaunchException("Chrome at $target did not answer /json/version in JSON", e)
            } ?: throw LaunchException("Chrome at $target did not give a browser WebSocket URL")
        return Triple(host, port, uri(url, "Chrome's WebSocket URL $url").rawPath)
    }

    /** [text] as a URI, or a [LaunchException] naming [what] it is. */
    private fun uri(text: String, what: String): URI =
        try {
            URI(text)
        } catch (e: URISyntaxException) {
            throw LaunchException("$what is not a valid address: ${e.reason}", e)
        }

    private fun hostOf(uri: URI): String =
        uri.host ?: throw LaunchException("No host in --attach $target")

    private fun portOf(uri: URI): Int =
        uri.port.takeIf { it in 1..MAX_PORT }
            ?: throw LaunchException("No port from 1 to $MAX_PORT in --attach $target")

    private class AttachedChrome(
        override val connection: CdpBrowserConnection,
        override val product: String,
    ) : RunningBrowser {
        override val pid: Long? = null

        override suspend fun stop() {
            connection.close()
        }
    }

    @Serializable private data class VersionInfo(val webSocketDebuggerUrl: String? = null)

    private companion object {
        const val CONNECT_MS = 5_000
        const val MAX_VERSION_BODY = 64 * 1024
        const val MAX_PORT = 65_535
    }
}

/**
 * Keeps one browser running. The first call starts it; when it has exited, as when its last window
 * is closed, the next call starts it again.
 *
 * A launch runs in a child of [scope] on [io], not in the call that asked for it, and callers wait
 * for its result. A caller that gives up only stops waiting: the launch goes on, and what it starts
 * is kept for the next caller. Were the launch to run in the caller, a cancel during startup would
 * drop a running Chrome that nothing closes, and since Chrome allows one process per profile, every
 * later launch would hand off to it and fail.
 */
class BrowserSupervisor(
    scope: CoroutineScope,
    private val io: CoroutineContext,
    private val start: suspend () -> RunningBrowser,
) : Closeable {
    /** Owns the launches, so [close] cancels them and a failed one doesn't cancel [scope]. */
    private val launches =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val lock = Any()
    @Volatile private var running: RunningBrowser? = null
    /** The latest launch. Once it completes, its browser is in [running] if it started one. */
    private var launching: Deferred<RunningBrowser>? = null
    private var closed = false

    /** The browser if it is up, without starting it. */
    val current: RunningBrowser?
        get() = running?.takeIf { it.connection.isOpen }

    @Suppress("MissingUseCall") // The supervisor owns the browser and closes it.
    suspend fun connection(): CdpBrowserConnection = browser().connection

    suspend fun browser(): RunningBrowser {
        val launch =
            synchronized(lock) {
                if (closed) throw shutDown()
                running
                    ?.takeIf { it.connection.isOpen }
                    ?.let {
                        return it
                    }
                launching?.takeUnless { it.isCompleted } ?: launch().also { launching = it }
            }
        // join throws only when this caller is cancelled. A launch that close() cancelled has no
        // browser to give, and awaiting it would throw a cancel this caller didn't get.
        launch.join()
        if (!launches.isActive) throw shutDown()
        return launch.await()
    }

    /** Starts a browser in [launches], closing the one that exited first. Holds [lock]. */
    @Suppress("MissingUseCall") // The launch keeps the browser in running, or closes it.
    private fun launch(): Deferred<RunningBrowser> {
        val stale = running
        running = null
        return launches.async(io) {
            stale?.let { stopStale(it) }
            val started =
                try {
                    start()
                } catch (e: LaunchException) {
                    throw ApiException(503, "browser_unavailable", e.message.orEmpty(), cause = e)
                }
            val kept =
                synchronized(lock) {
                    if (!closed) running = started
                    !closed
                }
            if (!kept) {
                // close() came while a start that doesn't stop for a cancel was finishing.
                withContext(NonCancellable) { started.stop() }
                throw shutDown()
            }
            started
        }
    }

    /**
     * Stops a browser that exited, before the next one starts on its profile. A stop that fails
     * leaves nothing for the next launch to wait for, so the launch goes on.
     */
    private suspend fun stopStale(stale: RunningBrowser) {
        logger.info("The browser exited; starting it again")
        try {
            stale.stop()
        } catch (e: IOException) {
            logger.warn("Could not close the browser that exited", e)
        }
    }

    /** Cancels a launch in progress, which closes what it made, and closes the browser. */
    override fun close() {
        val browser =
            synchronized(lock) {
                closed = true
                launching = null
                running.also { running = null }
            }
        launches.cancel()
        browser?.close()
    }

    private fun shutDown() =
        ApiException.unavailable(
            "browser_unavailable",
            "FingertipsMCP for Chrome is shutting down.",
        )
}

/**
 * Cancels [work] and waits for it to stop, then closes [browser]. Work that still uses the browser,
 * such as the cleanup that runs once the last session lets go of the screen, is cancelled rather
 * than failing on a browser that is closing. A call blocked in I/O stops only when it returns, so
 * the wait gives up after [WORK_STOP_MS] and closes the browser anyway.
 */
internal suspend fun stopWorkThenBrowser(work: Job, browser: BrowserSupervisor) {
    withTimeoutOrNull(WORK_STOP_MS) { work.cancelAndJoin() }
    browser.close()
}

/**
 * Stops the Chrome that [connection] drives over [process]'s pipe. It asks Chrome to close, which
 * saves its session, and gives that [CLOSE_MS]. Then it closes the pipe, which Chrome also exits
 * on, and terminates the process, which waits once for the exit before it kills. Closing the pipe
 * and terminating are both requests to exit, and on Windows they are the same one, so waiting after
 * each would only make a hung Chrome take [EXIT_WAIT_MS] longer to kill. A caller cancelled
 * meanwhile still waits for the exit, and for the kill when Chrome doesn't exit, so no Chrome is
 * left holding the profile.
 */
internal suspend fun stopChrome(process: Process, connection: CdpBrowserConnection) {
    try {
        if (process.isAlive && connection.isOpen) {
            withTimeoutOrNull(CLOSE_MS) {
                try {
                    connection.root.call("Browser.close")
                } catch (_: ApiException) {
                    // Closing the pipe below ends it anyway.
                }
            }
        }
    } finally {
        withContext(NonCancellable) {
            connection.close()
            process.terminate()
        }
    }
}

/**
 * Asks this process to exit, with SIGTERM or on Windows by closing its DevTools pipe, and kills it
 * when it has not exited within [EXIT_WAIT_MS], as a Chrome whose main thread is stuck does not.
 */
private suspend fun Process.terminate() {
    destroy()
    if (!exited()) destroyForcibly()
}

/** Whether this process exits within [EXIT_WAIT_MS]. */
private suspend fun Process.exited(): Boolean =
    withTimeoutOrNull(EXIT_WAIT_MS) { onExit().await() } != null

private const val CLOSE_MS = 3_000L
private const val EXIT_WAIT_MS = 5_000L
private const val WORK_STOP_MS = 2_000L

private suspend fun browserVersion(connection: CdpBrowserConnection): BrowserVersion =
    connection.root.call("Browser.getVersion").decodeCdp<BrowserVersion>() ?: BrowserVersion()

@Serializable
private data class BrowserVersion(val product: String = "Chrome", val userAgent: String? = null)

/**
 * The user agent a windowed Chrome would send where headless Chrome [reported] its own, or null
 * when it is that already. A windowed Chrome names itself `Chrome`, where headless Chrome says
 * `HeadlessChrome`, and its major version is the one in [product], such as `Chrome/157.0.8103.39`.
 * A user agent given with `--user-agent` is reported as given, so after an update the version in it
 * is changed. Null when [product] names no version.
 */
internal fun windowedUserAgent(reported: String, product: String): String? {
    val major = PRODUCT_MAJOR.find(product)?.groupValues?.get(1) ?: return null
    val wanted = reported.replace("HeadlessChrome/", "Chrome/").replace(UA_MAJOR, "Chrome/$major")
    return wanted.takeIf { it != reported }
}

/** A list of Chrome flags has [name], as `--name` alone or `--name=value`. */
private fun List<String>.hasSwitch(name: String): Boolean = any {
    it == name || it.startsWith("$name=")
}

private val PRODUCT_MAJOR = Regex("""^(?:Headless)?Chrome/(\d+)\.""")
private val UA_MAJOR = Regex("""\bChrome/\d+""")
private const val HEADLESS_WIDTH = 1920
private const val HEADLESS_HEIGHT = 1080

/**
 * The bar a desktop keeps along an edge of the screen, which windows don't cover, in CSS pixels at
 * its default size. Without one, the screen's available height is its whole height, which bot
 * checks read as a screen with no desktop around it. Headless Chrome gets the one for the OS its
 * user agent names.
 */
enum class DesktopBar(val top: Int, val bottom: Int) {
    /** GNOME's top bar. */
    LINUX(top = 32, bottom = 0),
    /** The menu bar since macOS 11, on a Mac without a notch. */
    MAC(top = 24, bottom = 0),
    /** The taskbar of Windows 11. */
    WINDOWS(top = 0, bottom = 48);

    companion object {
        fun forOs(osName: String = System.getProperty("os.name").orEmpty()): DesktopBar =
            when {
                isWindows(osName) -> WINDOWS
                isMac(osName) -> MAC
                else -> LINUX
            }
    }
}

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
