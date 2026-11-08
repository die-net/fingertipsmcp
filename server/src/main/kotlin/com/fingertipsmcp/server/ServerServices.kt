package com.fingertipsmcp.server

import com.fingertipsmcp.core.AppsService
import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.core.ClipboardService
import com.fingertipsmcp.core.InputService
import com.fingertipsmcp.core.LogcatFilter
import com.fingertipsmcp.core.PowerService
import com.fingertipsmcp.core.ShellResult
import com.fingertipsmcp.core.UiService
import com.fingertipsmcp.core.WaitService
import com.fingertipsmcp.lease.ForegroundWatch
import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.lease.ScreenLease
import com.fingertipsmcp.mcp.DESKTOP_TOOL_DESCRIPTIONS
import com.fingertipsmcp.mcp.SessionControl
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.Capabilities
import com.fingertipsmcp.model.CreatedToken
import com.fingertipsmcp.model.DeviceInfo
import com.fingertipsmcp.model.Event
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.TokenInfo
import com.fingertipsmcp.model.openapi.ApiGroup
import com.fingertipsmcp.stream.VideoSpec
import com.fingertipsmcp.tabs.Tabs
import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

/**
 * The REST and MCP handlers' view of the process. Every server has the core: tokens, device facts,
 * events, waits, the browser, the screen lease, and tabs. The rest are feature groups a product may
 * leave out; routes, MCP tools, and the OpenAPI document cover only the groups present.
 * [com.fingertipsmcp.AppGraph] provides every group; FingertipsMCP for Chrome provides the browser
 * and screenshots. Tests install [fingertipsModule] with fakes, without constructing Android
 * services.
 */
data class ServerServices(
    val waits: WaitService,
    val browser: BrowserService,
    val tokens: TokenGateway,
    val ioDispatcher: CoroutineDispatcher,
    val device: DeviceFacts,
    val events: Flow<Event>,
    /** The one screen lease, shared by MCP sessions, the viewer, and REST calls. */
    val lease: ScreenLease,
    /** Who owns which browser tab. */
    val tabs: Tabs,
    /** Catches a session without the screen bringing an app forward. */
    val foreground: ForegroundWatch,
    val product: Product = Product.ANDROID,
    val phone: PhoneServices? = null,
    val streams: StreamServices? = null,
    val screenshots: (suspend (Float) -> ByteArray)? = null,
    /** The MCP session these services are for, in a session's copy; null for REST. */
    val holder: Holder.Session? = null,
    /** What that session's tools can do with sessions; null for REST. */
    val sessions: SessionControl? = null,
) {
    /** The feature groups present, always including [ApiGroup.CORE] and [ApiGroup.BROWSER]. */
    val groups: Set<ApiGroup> = buildSet {
        add(ApiGroup.CORE)
        add(ApiGroup.BROWSER)
        if (phone != null) add(ApiGroup.PHONE)
        if (streams != null) add(ApiGroup.STREAMS)
        if (screenshots != null) add(ApiGroup.SCREENSHOTS)
    }
}

/** The phone group, for a tool or route that is offered only when it is present. */
fun ServerServices.requirePhone(): PhoneServices =
    phone ?: throw missing("Android's screen and apps")

fun ServerServices.requireScreenshots(): suspend (Float) -> ByteArray =
    screenshots ?: throw missing("screenshots")

private fun ServerServices.missing(what: String) =
    ApiException(422, "unsupported", "${product.title} does not have $what.")

/** Names, wording, and behavior that differ between products. */
data class Product(
    /** The MCP server name, and the scheme of resource URIs. */
    val name: String,
    /** The OpenAPI title, and the name in messages. */
    val title: String,
    /** What the product drives, for the MCP instructions, such as "one Android phone". */
    val drives: String,
    /** How `auto` browser input resolves, and which input takes the screen. */
    val browserInput: BrowserInputPolicy,
    /** MCP tool descriptions that replace the phone's wording, by tool name. */
    val toolDescriptions: Map<String, String> = emptyMap(),
) {
    companion object {
        val ANDROID =
            Product(
                "fingertips-android",
                "FingertipsMCP for Android",
                "one Android phone",
                BrowserInputPolicy.PHONE,
            )
        val CHROME =
            Product(
                "fingertips-chrome",
                "FingertipsMCP for Chrome",
                "one Chrome browser",
                BrowserInputPolicy.DESKTOP,
                DESKTOP_TOOL_DESCRIPTIONS,
            )
    }
}

/** Android's screen, input, apps, power, clipboard, log, and the stored lock-screen PIN. */
data class PhoneServices(
    val ui: UiService,
    val input: InputService,
    val apps: AppsService,
    val power: PowerService,
    val clipboard: ClipboardService,
    val writePin: (String?) -> Unit,
    /** Runs a command as the shell user, for `logcat_tail`. */
    val shell: suspend (List<String>) -> ShellResult,
    val logcat: LogcatFeed,
)

/** The WebCodecs viewer, the media sockets it plays, and what its input does on this product. */
data class StreamServices(
    val video: VideoFeed,
    val audio: AudioFeed,
    /** How to reach the viewer, for the MCP resource. */
    val viewerText: () -> String,
    val controls: ViewerControls,
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
