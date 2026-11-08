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
 * [com.fingertipsmcp.AppGraph] provides every group; FingertipsMCP for Chrome provides all but the
 * phone. Tests install [fingertipsModule] with fakes, without constructing Android services.
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
    val product: Product,
    val phone: PhoneServices? = null,
    val streams: StreamServices? = null,
    val screenshots: ScreenshotService? = null,
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

fun ServerServices.requireScreenshots(): ScreenshotService =
    screenshots ?: throw missing("screenshots")

/**
 * Whether [group] is present and its tools can work with [capabilities] now. Screenshots can only
 * while their service can take one, such as while the phone's helper runs.
 */
internal fun ServerServices.offers(group: ApiGroup, capabilities: Capabilities): Boolean =
    group in groups &&
        (group != ApiGroup.SCREENSHOTS || screenshots?.available?.invoke(capabilities) == true)

fun ServerServices.requireStreams(): StreamServices = streams ?: throw missing("the viewer")

private fun ServerServices.missing(what: String) =
    ApiException(422, "unsupported", "${product.title} does not have $what.")

/**
 * Names, wording, and behavior that differ between products. Wording that depends on the product is
 * here; code that depends on what a server can do asks for its feature group in
 * [ServerServices.groups].
 */
data class Product(
    /** The MCP server name, and the scheme of resource URIs. */
    val name: String,
    /** The OpenAPI title, and the name in messages. */
    val title: String,
    /** What the product drives, for the MCP instructions, such as "one Android phone". */
    val drives: String,
    /** What parallel tasks share, for the MCP instructions, such as "phone". */
    val device: String,
    /** How browser work shares the screen, as a line of the MCP instructions. */
    val backgroundWork: String,
    /** What letting the screen go with cleanup does, for `screen_release` and its REST twin. */
    val releaseCleanup: String,
    /** How `auto` browser input resolves, and which input takes the screen. */
    val browserInput: BrowserInputPolicy,
    /**
     * Picks an MCP tool's description from its phone wording and its desktop wording, which is null
     * for a tool whose phone wording fits everywhere.
     */
    val toolText: (phone: String, desktop: String?) -> String,
) {
    companion object {
        val ANDROID =
            Product(
                name = "fingertips-android",
                title = "FingertipsMCP for Android",
                drives = "one Android phone",
                device = "phone",
                backgroundWork =
                    "Start browser work in the background, unless you already know the page " +
                        "does not work there. Take the screen only for what needs it, such as " +
                        "apps or a captcha.",
                releaseCleanup =
                    "FingertipsMCP hides the keyboard and goes home before the next client gets " +
                        "it, and locks the phone if no one is waiting and it was locked before.",
                browserInput = BrowserInputPolicy.PHONE,
                toolText = { phone, _ -> phone },
            )
        val CHROME =
            Product(
                name = "fingertips-chrome",
                title = "FingertipsMCP for Chrome",
                drives = "one Chrome browser",
                device = "browser",
                backgroundWork =
                    "Reading and scripts run in the background. Clicks, scrolls, and typing use " +
                        "the screen, which here is the browser's one mouse and keyboard.",
                releaseCleanup =
                    "The browser goes back to a blank tab, so yours is hidden until you act on it.",
                browserInput = BrowserInputPolicy.DESKTOP,
                toolText = { phone, desktop -> desktop ?: phone },
            )
    }
}

/**
 * Android's screen, input, apps, power, clipboard, log, and the stored lock-screen PIN, and the
 * watch for a page bringing an app forward.
 */
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
    /** Catches a session without the screen bringing an app forward. */
    val foreground: ForegroundWatch,
)

/** Screenshots of what the screen shows, as PNGs, for the screenshots group. */
data class ScreenshotService(
    /**
     * Whether [png] can work with these capabilities, which decides whether the `screenshot` tool
     * is listed. On a phone that is while the helper runs; a desktop always can.
     */
    val available: (Capabilities) -> Boolean,
    /** A PNG at a scale from 0.1 to 1. */
    val png: suspend (Float) -> ByteArray,
)

/** The WebCodecs viewer, the media sockets it plays, and what its input does on this product. */
data class StreamServices(
    val video: VideoFeed,
    /**
     * Why [video] cannot stream on this device now, such as a phone without its helper, or null
     * when it can. The viewer takes the reason as final.
     */
    val videoRefusal: () -> String?,
    val audio: AudioFeed,
    /** Why [audio] cannot stream on this device now, or null when it can, as [videoRefusal]. */
    val audioRefusal: () -> String?,
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
