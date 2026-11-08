package com.fingertipsmcp.browser

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserTarget
import com.fingertipsmcp.model.DevtoolsSocketInfo
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.CoroutineContext
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

    /** The app that shows [tabs]' pages, which can be brought forward; null on a desktop. */
    val tabsPackage: String?

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
     * A page session held open by the caller, such as for a wait. Reads give up after
     * [readTimeoutMs], or never for 0, where the endpoint has a read timeout at all. [opening] gets
     * what the session opens on, such as a socket, before any blocking read, so a caller that has
     * to stop on time can close it to end a handshake that hangs. It may throw to abandon the open.
     */
    suspend fun openPage(
        endpoint: String,
        targetId: String,
        readTimeoutMs: Long,
        opening: (Closeable) -> Unit = {},
    ): OpenCdpSession

    /** A browser session held open by the caller, with reads and [opening] as [openPage] says. */
    suspend fun openBrowser(
        endpoint: String,
        readTimeoutMs: Long,
        opening: (Closeable) -> Unit = {},
    ): OpenCdpSession

    /** Why there is nothing to attach to. */
    fun gap(gap: BrowserGap): String
}

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

    override val tabsPackage: String = CHROME_PACKAGE

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
        readTimeoutMs: Long,
        opening: (Closeable) -> Unit,
    ): OpenCdpSession = openSession(endpoint, "/devtools/page/$targetId", readTimeoutMs, opening)

    override suspend fun openBrowser(
        endpoint: String,
        readTimeoutMs: Long,
        opening: (Closeable) -> Unit,
    ): OpenCdpSession = openSession(endpoint, "/devtools/browser", readTimeoutMs, opening)

    @Suppress("MissingUseCall") // SocketCdpSession closes the socket.
    private suspend fun openSession(
        name: String,
        path: String,
        timeoutMs: Long,
        opening: (Closeable) -> Unit,
    ): OpenCdpSession {
        // Not in withContext: its return would drop a socket that opened after a cancel, which then
        // never closes. [open] moves its blocking work off the caller's thread itself.
        val devtools = open(name, timeoutMs)
        var opened = false
        try {
            opening(devtools)
            val session = devtools.withStreams { input, output ->
                withContext(io) {
                    CdpSocketSession(input, output, io, websocketKey).also { it.open(path) }
                }
            }
            opened = true
            return SocketCdpSession(session, devtools)
        } finally {
            if (!opened) devtools.close()
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
