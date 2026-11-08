package com.fingertipsmcp.browser

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.core.closeOnFailure
import com.fingertipsmcp.core.openOn
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserTarget
import com.fingertipsmcp.model.DevtoolsSocketInfo
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer

/**
 * How DevTools targets are reached. Android opens an abstract socket and a WebSocket per target
 * ([SocketEndpoints]); a desktop browser attaches flattened sessions over one connection
 * ([ConnectionEndpoints]). Target ids read `endpoint~targetId` either way.
 *
 * A page that has closed fails to open with an [ApiException] for which [tabGone] is true, on
 * either kind of endpoint.
 */
interface DevtoolsEndpoints {
    /** The endpoint of the browser that opens tabs: Chrome's socket on Android. */
    val tabs: String

    /**
     * Whether the browser at [tabs] keeps a tab that `Target.createTarget` opens with `background`
     * hidden. A desktop Chrome does. Chrome on Android ignores the flag.
     */
    val backgroundStaysHidden: Boolean

    /** Every endpoint that is up. Empty when nothing is debuggable. */
    suspend fun endpoints(): List<DevtoolsSocketInfo>

    /** The pages one endpoint lists. */
    suspend fun targets(endpoint: DevtoolsSocketInfo): List<BrowserTarget>

    /** Runs [block] on a session with one page, closed after. */
    suspend fun <T> withPage(
        endpoint: String,
        targetId: String,
        block: suspend (CdpSession) -> T,
    ): T

    /**
     * Runs [block] on a session with the browser itself, for the `Target` and `Browser` domains,
     * closed after. Domains it enables end with it.
     */
    suspend fun <T> withBrowser(endpoint: String, block: suspend (CdpSession) -> T): T

    /**
     * A page session held open by the caller, such as for a wait. The browser has [answerTimeoutMs]
     * to answer the open, or the endpoint's own limit for 0, or the open fails with `cdp_timeout`.
     * Once open, each call has its own timeout. Cancelling the caller ends the open, a handshake
     * that hangs included, and leaves nothing open.
     */
    suspend fun openPage(endpoint: String, targetId: String, answerTimeoutMs: Long): OpenCdpSession

    /** A browser session held open by the caller, opened as [openPage] says. */
    suspend fun openBrowser(endpoint: String, answerTimeoutMs: Long): OpenCdpSession

    /** Why there is nothing to attach to. */
    fun gap(gap: BrowserGap): String
}

/**
 * The app that shows the pages of the DevTools endpoint [endpoint], which can be brought forward:
 * Chrome for Chrome's socket on Android, and null for any other, a desktop browser's included.
 */
fun tabsPackageOf(endpoint: String): String? = CHROME_PACKAGE.takeIf { endpoint == CHROME_SOCKET }

/**
 * A page could not be attached to because the browser no longer lists it. It is the cause of the
 * `cdp_error` the attach throws, as [WebSocketRefused] is for a socket.
 */
internal class TargetMissing(targetId: String) : IOException("No target with id $targetId")

/**
 * Android's DevTools: the helper lists abstract sockets, and each call opens one, then speaks HTTP
 * for `/json/list` or upgrades to a WebSocket on the target's path. [listSockets] and [open] map
 * helper failures to [ApiException] themselves. [open] takes a read timeout in milliseconds, 0 for
 * none, and runs its blocking work on a dispatcher of its own choosing.
 */
class SocketEndpoints(
    private val io: CoroutineContext,
    private val listSockets: suspend () -> String,
    private val open: suspend (String, Long) -> DevtoolsSocket,
    private val websocketKey: () -> String = ::websocketKey,
    private val targetTimeoutMs: Long = TARGET_LIST_MS,
    private val readTimeoutMs: Long = READ_MS,
    private val devtoolsGap: (BrowserGap) -> String = { gap ->
        browserGapMessage(gap, chromeInstalled = true, usbDebugging = true)
    },
) : DevtoolsEndpoints {
    override val tabs: String = CHROME_SOCKET

    override val backgroundStaysHidden: Boolean = false

    override fun gap(gap: BrowserGap): String = devtoolsGap(gap)

    override suspend fun endpoints(): List<DevtoolsSocketInfo> =
        withContext(io) {
            val json = listSockets()
            try {
                ApiJson.decodeFromString(serializer<List<DevtoolsSocketInfo>>(), json)
            } catch (e: SerializationException) {
                throw ApiException(
                    503,
                    "helper_error",
                    "The helper returned ${json.take(ERROR_SNIPPET)}",
                    cause = e,
                )
            }
        }

    /**
     * Chrome's tabs come from `Target.getTargets`, whose ids `Target.createTarget` uses too, and a
     * WebView's pages from `/json/list`.
     */
    override suspend fun targets(endpoint: DevtoolsSocketInfo): List<BrowserTarget> {
        if (endpoint.name != tabs) {
            return listJsonTargets(
                // Not in withContext, as in openSession. listJsonTargets closes the socket.
                devtools = open(endpoint.name, readTimeoutMs),
                endpoint.name,
                endpoint.pid,
                endpoint.packageName,
                io,
                targetTimeoutMs,
            )
        }
        val result = withBrowser(endpoint.name) { it.call("Target.getTargets") }
        return chromeTargets(result, endpoint.name, endpoint.pid, endpoint.packageName)
    }

    override suspend fun <T> withPage(
        endpoint: String,
        targetId: String,
        block: suspend (CdpSession) -> T,
    ): T = useCdp(endpoint, "/devtools/page/$targetId", block)

    override suspend fun <T> withBrowser(endpoint: String, block: suspend (CdpSession) -> T): T =
        useCdp(endpoint, "/devtools/browser", block)

    override suspend fun openPage(
        endpoint: String,
        targetId: String,
        answerTimeoutMs: Long,
    ): OpenCdpSession = openSession(endpoint, "/devtools/page/$targetId", answerTimeoutMs)

    override suspend fun openBrowser(endpoint: String, answerTimeoutMs: Long): OpenCdpSession =
        openSession(endpoint, "/devtools/browser", answerTimeoutMs)

    @Suppress("MissingUseCall") // SocketCdpSession closes the socket.
    private suspend fun openSession(name: String, path: String, timeoutMs: Long): OpenCdpSession {
        // Not in withContext: its return would drop a socket that opened after a cancel, which then
        // never closes. [open] moves its blocking work off the caller's thread itself.
        val devtools = open(name, timeoutMs)
        return devtools.closeOnFailure {
            val session = devtools.withStreams { input, output ->
                openOn(io) {
                    closedOnCancel(devtools) {
                        CdpSocketSession(input, output, io, websocketKey).closeOnFailure {
                            it.open(path)
                            it
                        }
                    }
                }
            }
            SocketCdpSession(session, devtools)
        }
    }

    /**
     * Runs [handshake], a blocking read that a cancel alone can't end, and closes [socket] under it
     * if the caller is cancelled meanwhile, which ends the read.
     */
    private fun <T> CoroutineScope.closedOnCancel(socket: Closeable, handshake: () -> T): T {
        val reading = AtomicBoolean(true)
        val closer =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (reading.get()) socket.close()
                }
            }
        try {
            return handshake()
        } finally {
            reading.set(false)
            closer.cancel()
        }
    }

    private suspend fun <T> useCdp(
        socket: String,
        path: String,
        block: suspend (CdpSession) -> T,
    ): T =
        withContext(io) {
            open(socket, readTimeoutMs).use { devtools ->
                devtools.withStreams { input, output ->
                    CdpSocketSession(input, output, io, websocketKey).use { cdp ->
                        cdp.open(path)
                        block(cdp)
                    }
                }
            }
        }

    private companion object {
        const val ERROR_SNIPPET = 80
        const val TARGET_LIST_MS = 5_000L
        const val READ_MS = 15_000L
    }
}
