package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.BrowserServiceImpl
import com.fingertipsmcp.browser.ConnectionEndpoints
import com.fingertipsmcp.browser.DESKTOP_ENDPOINT
import com.fingertipsmcp.browser.DesktopPointer
import com.fingertipsmcp.browser.DesktopStyle
import com.fingertipsmcp.browser.parseBrowserTargetId
import com.fingertipsmcp.core.EventBus
import com.fingertipsmcp.lease.ForegroundWatch
import com.fingertipsmcp.lease.ScreenLease
import com.fingertipsmcp.mcp.localMcp
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.server.Product
import com.fingertipsmcp.server.ServerServices
import com.fingertipsmcp.server.StreamServices
import com.fingertipsmcp.server.TokenStore
import com.fingertipsmcp.server.fingertipsModule
import com.fingertipsmcp.server.mcpSessions
import com.fingertipsmcp.tabs.FileTabStore
import com.fingertipsmcp.tabs.Tabs
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory

/**
 * fingertips-chrome's object graph: one browser, the browser service with desktop input,
 * browser-only waits, tab screenshots, the screen lease and tab ownership, and the same routes and
 * MCP tools as fingertips for those groups. The screen here is the browser's one mouse and
 * keyboard.
 */
class ChromeApp(
    private val options: ServeOptions,
    private val paths: ChromePaths,
    version: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    background: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + background)
    private val profile = options.profile?.let(::File) ?: paths.profile
    val supervisor = BrowserSupervisor(scope, io, ::startBrowser)
    @Suppress("MissingUseCall") // The supervisor owns the browser connection.
    private val devtools =
        ConnectionEndpoints(
            connection = { supervisor.connection() },
            pid = { supervisor.current?.pid?.toInt() },
            missing = "The browser is not running and could not be started.",
        )
    private val device = HostDevice(supervisor, version)
    /** Chrome's one mouse and keyboard, which the agent's input and the viewer's share. */
    private val pointer = DesktopPointer(DesktopStyle.forHost())
    private val browser =
        BrowserServiceImpl(
            devtools = devtools,
            desktopInput = pointer,
            scope = scope,
        )
    // Cleanup runs only after a holder lets go, long after tabs exists.
    @Suppress("PropertyUsedBeforeDeclaration")
    private val lease: ScreenLease =
        ScreenLease(
            scope,
            // Background means background, here as on a phone: no session's tab stays showing
            // once it lets go, so a workflow can't come to depend on its tab being on screen.
            cleanup = {
                tabs.showPlaceholder()
                emptyList()
            },
            lockedNow = { false },
            cleanedNote = { null },
        )
    private val tabs: Tabs =
        Tabs(
            browser,
            lease,
            inFront = { true },
            store = FileTabStore(File(paths.config, "session-tabs")),
            scope = scope,
            chrome = DESKTOP_ENDPOINT,
        )
    private val shots = TabScreenshots(browser, devtools)
    @Suppress("MissingUseCall") // The supervisor owns the browser connection.
    private val viewer =
        ChromeViewer(
            connection = { supervisor.connection() },
            browser = browser,
            extensionHome = paths.data,
            scope = scope,
            pointer = pointer,
        )
    /** The TCP port HTTP listens on, for the viewer's address; null until then, or with none. */
    @Volatile private var viewerPort: Int? = null
    val services =
        ServerServices(
            waits = BrowserWaits(browser, devtools, io),
            browser = browser,
            tokens = TokenStore(paths.config),
            ioDispatcher = io,
            device = device,
            events = EventBus().events,
            lease = lease,
            tabs = tabs,
            // Pages cannot bring another app forward on a desktop, so nothing is watched.
            foreground =
                ForegroundWatch(lease, scope, top = { null }, bringBack = {}, report = { _, _ -> }),
            product = Product.CHROME,
            screenshots = shots::png,
            streams =
                StreamServices(
                    video = viewer.video,
                    audio = viewer.audio,
                    viewerText = ::viewerText,
                    controls = viewer.controls,
                ),
        )
    private val sessions = mcpSessions(services, scope)

    /** HTTP API and MCP at `/mcp`, on loopback TCP or a Unix socket, until the process ends. */
    fun serveHttp(port: Int) {
        warnIfExposed()
        val socket = options.unixSocket?.let(::File)
        if (socket != null) {
            // sockaddr_un holds 104 bytes on macOS and 108 on Linux, with the terminating NUL.
            if (socket.path.toByteArray().size >= MAX_SOCKET_PATH) {
                throw UsageException(
                    "--unix path is too long for a Unix socket (at most ${MAX_SOCKET_PATH - 1} " +
                        "bytes): ${socket.path}"
                )
            }
            if (socket.exists()) socket.delete()
        } else {
            requirePortFree(port)
            viewerPort = port
        }
        val server =
            embeddedServer(
                CIO,
                applicationEnvironment {},
                configure = {
                    if (socket != null) {
                        unixConnector(socket.path)
                    } else {
                        connector {
                            host = options.host
                            this.port = port
                        }
                    }
                },
                module = { fingertipsModule(services, sessions, mcpDnsRebindingProtection = true) },
            )
        startBrowserEagerly()
        Runtime.getRuntime()
            .addShutdownHook(
                Thread {
                    server.stop(gracePeriodMillis = STOP_GRACE_MS, timeoutMillis = STOP_TIMEOUT_MS)
                    supervisor.close()
                }
            )
        try {
            server.start(wait = false)
        } catch (e: IOException) {
            throw listenFailure(socket, port, e)
        } catch (e: CancellationException) {
            // CIO binds in a coroutine and reports a failed bind by cancelling the start.
            throw listenFailure(socket, port, e)
        }
        if (socket != null) {
            ownerOnly(socket)
            logger.info("fingertips-chrome listening on unix socket {}", socket.path)
        } else {
            logger.info("fingertips-chrome listening on http://{}:{}", options.host, port)
        }
        Thread.currentThread().join()
    }

    /** How to open the viewer, for the MCP resource. */
    private fun viewerText(): String {
        val port =
            viewerPort
                ?: return "The viewer needs FingertipsMCP for Chrome serving HTTP on a TCP port."
        return "http://127.0.0.1:$port/viewer?access_token=TOKEN\n" +
            "Replace TOKEN with a token that has the stream scope, and the control scope to use " +
            "the tab. That URL sets a cookie and redirects to /viewer without the token. The " +
            "viewer shows the tab Chrome shows, with its sound, and takes the mouse and keyboard."
    }

    /**
     * MCP for one client on stdin and stdout, as an MCP client starts a local server. Logs go to
     * stderr, so stdout carries only the protocol.
     */
    @Suppress("MissingUseCall") // The transport reads stdin until the client closes it.
    fun serveStdio() {
        Runtime.getRuntime().addShutdownHook(Thread { supervisor.close() })
        startBrowserEagerly()
        runBlocking {
            val local = localMcp(services, sessions, STDIO_NAME, STDIO_SCOPES)
            val server = local.server
            val closed = CompletableDeferred<Unit>()
            server.onClose { closed.complete(Unit) }
            // The client ends the session by closing stdin; the SDK does not report that.
            val input = EndOfInput(System.`in`) { closed.complete(Unit) }
            val transport =
                StdioServerTransport(input.asSource().buffered(), System.out.asSink().buffered()) {}
            server.createSession(transport)
            closed.await()
            local.end()
        }
        supervisor.close()
    }

    @Suppress("MissingUseCall") // The supervisor takes the started browser and closes it.
    private suspend fun startBrowser(): RunningBrowser {
        val attach = options.attach
        if (attach != null) return ChromeAttacher(attach, io).start()
        val binary = ChromeLocator().find(options.chrome)
        if (binary.chromium) logger.warn(CHROMIUM_WARNING)
        val launcher =
            ChromeLauncher(
                binary = binary,
                profile = profile,
                headless = options.headless,
                extraArgs = options.chromeArgs,
                hideAutomation = !options.showAutomation,
                logFile = paths.chromeLog,
                io = io,
            )
        if (options.headless) {
            logger.warn(
                "Headless Chrome is the easiest kind to detect. Prefer a real desktop session."
            )
        }
        val started = launcher.start()
        scope.launch { measureScreen() }
        return started
    }

    @Suppress("MissingUseCall") // The supervisor keeps the browser.
    private fun startBrowserEagerly() {
        runBlocking {
            try {
                supervisor.browser()
            } catch (e: ApiException) {
                logger.error("Could not start the browser: {}", e.message)
            }
        }
    }

    private suspend fun measureScreen() {
        try {
            val page = browser.targets().firstOrNull { it.type == "page" } ?: return
            val (endpoint, targetId) = parseBrowserTargetId(page.id)
            devtools.withPage(endpoint, targetId) { cdp -> device.measure(cdp) }
        } catch (e: ApiException) {
            logger.debug("Could not read the screen size: {}", e.message)
        }
    }

    /** Fails before Chrome starts when the port is taken, rather than after. */
    private fun requirePortFree(port: Int) {
        try {
            ServerSocket().use { probe -> probe.bind(InetSocketAddress(options.host, port)) }
        } catch (e: IOException) {
            throw UsageException(
                "Could not listen on ${options.host}:$port: ${e.message.orEmpty()}",
                e,
            )
        }
    }

    private fun listenFailure(socket: File?, port: Int, error: Exception): UsageException {
        val reason = generateSequence<Throwable>(error) { it.cause }.last().message.orEmpty()
        val where = socket?.path ?: "${options.host}:$port"
        supervisor.close()
        return UsageException("Could not listen on $where: $reason", error)
    }

    private fun warnIfExposed() {
        if (options.unixSocket != null) return
        val loopback = runCatching { InetAddress.getByName(options.host).isLoopbackAddress }
        if (loopback.getOrDefault(false)) return
        logger.warn(
            "Listening on {}, which is not loopback. Anyone who can reach it and holds a token " +
                "can drive this browser, and MCP accepts only localhost Host headers.",
            options.host,
        )
    }

    private companion object {
        const val STDIO_NAME = "stdio"
        const val MAX_SOCKET_PATH = 104
        const val STOP_GRACE_MS = 200L
        const val STOP_TIMEOUT_MS = 1_000L
        /** A local MCP client started this process, so it gets what a paired client gets. */
        val STDIO_SCOPES = setOf(Scope.OBSERVE, Scope.CONTROL, Scope.BROWSER)
    }
}

/** Passes reads through and calls [onEnd] once the stream ends. */
private class EndOfInput(private val input: InputStream, private val onEnd: () -> Unit) :
    InputStream() {
    override fun read(): Int = input.read().also { if (it < 0) onEnd() }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        input.read(buffer, offset, length).also { if (it < 0) onEnd() }

    override fun available(): Int = input.available()

    override fun close() = input.close()
}

private val logger = LoggerFactory.getLogger("FingertipsChrome")
