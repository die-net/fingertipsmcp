package net.die.phoneapi.server

import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import net.die.phoneapi.core.AppsService
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.ClipboardService
import net.die.phoneapi.core.InputService
import net.die.phoneapi.core.LogcatFilter
import net.die.phoneapi.core.PowerService
import net.die.phoneapi.core.UiService
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.helperclient.ShellResult
import net.die.phoneapi.lease.ForegroundWatch
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.lease.ScreenLease
import net.die.phoneapi.mcp.SessionControl
import net.die.phoneapi.model.Capabilities
import net.die.phoneapi.model.CreatedToken
import net.die.phoneapi.model.DeviceInfo
import net.die.phoneapi.model.Event
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.TokenInfo
import net.die.phoneapi.stream.VideoSpec
import net.die.phoneapi.tabs.Tabs

/**
 * The REST and MCP handlers' view of the process. [net.die.phoneapi.AppGraph] builds the real one;
 * tests install [phoneApiModule] with fakes, without constructing Android services.
 */
data class ServerServices(
    val ui: UiService,
    val input: InputService,
    val apps: AppsService,
    val waits: WaitService,
    val browser: BrowserService,
    val power: PowerService,
    val clipboard: ClipboardService,
    val tokens: TokenGateway,
    val ioDispatcher: CoroutineDispatcher,
    val device: DeviceFacts,
    val screenshots: suspend (Float) -> ByteArray,
    val writePin: (String?) -> Unit,
    val events: Flow<Event>,
    val video: VideoFeed,
    val audio: AudioFeed,
    val viewerHtml: suspend () -> ByteArray,
    val shell: suspend (List<String>) -> ShellResult,
    val viewerText: () -> String,
    val logcat: LogcatFeed,
    /** The one screen lease, shared by MCP sessions, the viewer, and REST calls. */
    val lease: ScreenLease,
    /** Who owns which browser tab. */
    val tabs: Tabs,
    /** Catches a session without the screen bringing an app forward. */
    val foreground: ForegroundWatch,
    /** The MCP session these services are for, in a session's copy; null for REST. */
    val holder: Holder.Session? = null,
    /** What that session's tools can do with sessions; null for REST. */
    val sessions: SessionControl? = null,
)

/** Per-connection logcat. Not part of [ServerServices.events]; only clients that ask receive it. */
fun interface LogcatFeed {
    fun lines(filter: LogcatFilter): Flow<Event>
}

/** Bearer tokens. Implementations store only hashes. */
interface TokenGateway {
    fun authenticate(secret: String): TokenInfo?

    fun list(): List<TokenInfo>

    fun create(name: String, scopes: Set<Scope>): CreatedToken

    /** Replaces the scopes of token [id] and returns its info, or null when there is none. */
    fun setScopes(id: String, scopes: Set<Scope>): TokenInfo?

    fun revoke(id: String): Boolean
}

/** Device identity and the capability map, without the Android types that produce them. */
interface DeviceFacts {
    fun info(): DeviceInfo

    fun capabilities(): Capabilities

    fun versionName(): String
}

fun interface VideoFeed {
    suspend fun serve(session: DefaultWebSocketServerSession, spec: VideoSpec)
}

fun interface AudioFeed {
    suspend fun serve(session: DefaultWebSocketServerSession)
}
