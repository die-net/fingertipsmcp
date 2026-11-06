package net.die.phoneapi.model.openapi

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.AppInfo
import net.die.phoneapi.model.BrowserCaptureRequest
import net.die.phoneapi.model.BrowserCaptureResult
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.ClipboardContent
import net.die.phoneapi.model.ClipboardReadRequest
import net.die.phoneapi.model.ClipboardWriteRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.ConsoleResult
import net.die.phoneapi.model.CreateTokenRequest
import net.die.phoneapi.model.CreatedToken
import net.die.phoneapi.model.DeviceInfo
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.Event
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.FindResult
import net.die.phoneapi.model.GestureRequest
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.LaunchRequest
import net.die.phoneapi.model.NavigateRequest
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.OpenTabRequest
import net.die.phoneapi.model.OrientationRequest
import net.die.phoneapi.model.PinStatus
import net.die.phoneapi.model.PointerFrame
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SessionInfo
import net.die.phoneapi.model.SessionStarted
import net.die.phoneapi.model.SetPinRequest
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.TokenInfo
import net.die.phoneapi.model.UiSnapshot
import net.die.phoneapi.model.UnlockRequest
import net.die.phoneapi.model.UpdateTokenRequest
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.WaitResult

public enum class ApiMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
    WEBSOCKET,
}

/**
 * One REST or WebSocket route. [request] and [response] are null when that side has no JSON body.
 */
public data class ApiEndpoint(
    public val method: ApiMethod,
    public val path: String,
    /**
     * The scopes that let a token call it: any one of them does. Empty when any token may, as for
     * starting a session.
     */
    public val scopes: Set<Scope>,
    public val summary: String,
    public val request: KSerializer<*>? = null,
    public val response: KSerializer<*>? = null,
    public val status: Int = 200,
    public val responseContentType: String? = response?.let { JSON },
    /** The body's type when it is not JSON, such as an image. */
    public val requestContentType: String? = null,
) {
    public companion object {
        public const val JSON: String = "application/json"
        public const val PNG: String = "image/png"
        public const val HTML: String = "text/html"
        public const val IMAGE: String = "image/*"
    }
}

/** Every route installed from `server/routes`, in the order the server registers them. */
public val phoneApiEndpoints: List<ApiEndpoint> =
    listOf(
        endpoint(
            ApiMethod.GET,
            "/v1/device",
            setOf(Scope.OBSERVE),
            "Device identity, display, helper status, and capabilities.",
            response = serializer<DeviceInfo>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/device/wake",
            setOf(Scope.CONTROL, Scope.BROWSER),
            "Turn the screen on.",
            response = serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/device/unlock",
            setOf(Scope.CONTROL, Scope.BROWSER),
            "Dismiss the keyguard.",
            serializer<UnlockRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/device/lock",
            setOf(Scope.CONTROL),
            "Lock the device.",
            response = serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/device/orientation",
            setOf(Scope.CONTROL),
            "Turn auto-rotate on, or lock the screen to a rotation. An app can still force its own orientation.",
            serializer<OrientationRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.PUT,
            "/v1/device/pin",
            setOf(Scope.ADMIN),
            "Store a lock-screen PIN.",
            serializer<SetPinRequest>(),
            serializer<PinStatus>(),
        ),
        endpoint(
            ApiMethod.DELETE,
            "/v1/device/pin",
            setOf(Scope.ADMIN),
            "Forget the stored PIN.",
            status = 204,
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/ui/snapshot",
            setOf(Scope.OBSERVE),
            "Accessibility snapshot of the current UI.",
            response = serializer<UiSnapshot>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/ui/find",
            setOf(Scope.OBSERVE),
            "Find nodes matching a selector.",
            serializer<FindRequest>(),
            serializer<FindResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/ui/nodes/{ref}/action",
            setOf(Scope.CONTROL),
            "Perform an action on a snapshot node.",
            serializer<NodeActionRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/screenshot",
            setOf(Scope.OBSERVE),
            "PNG screenshot.",
            responseContentType = ApiEndpoint.PNG,
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/input/tap",
            setOf(Scope.CONTROL),
            "Tap a point or a node.",
            serializer<TapRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/input/swipe",
            setOf(Scope.CONTROL),
            "Swipe between points or inside a node.",
            serializer<SwipeRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/input/gesture",
            setOf(Scope.CONTROL),
            "Replay pointer paths.",
            serializer<GestureRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.WEBSOCKET,
            "/v1/input/pointer",
            setOf(Scope.CONTROL),
            "Live pointer contacts as JSON text frames.",
            serializer<PointerFrame>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/input/key",
            setOf(Scope.CONTROL),
            "Press a key.",
            serializer<KeyRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/input/text",
            setOf(Scope.CONTROL),
            "Type text.",
            serializer<TextRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/ime/hide",
            setOf(Scope.CONTROL),
            "Hide the soft keyboard.",
            response = serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/ime/show",
            setOf(Scope.CONTROL),
            "Focus an editable node and show the soft keyboard.",
            serializer<ImeShowRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/clipboard/read",
            setOf(Scope.CONTROL),
            "Read the clipboard's text, optionally pressing KEYCODE_COPY first.",
            serializer<ClipboardReadRequest>(),
            serializer<ClipboardContent>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/clipboard/write",
            setOf(Scope.CONTROL),
            "Put text on the clipboard, optionally pressing KEYCODE_PASTE after.",
            serializer<ClipboardWriteRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/clipboard/image",
            setOf(Scope.CONTROL),
            "The clipboard's image, in the type the copying app gave it. 404 when it holds none.",
            responseContentType = ApiEndpoint.IMAGE,
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/clipboard/image",
            setOf(Scope.CONTROL),
            "Put a PNG, JPEG, GIF, or WebP body on the clipboard. ?paste=true presses KEYCODE_PASTE after.",
            response = serializer<ActionResult>(),
            requestContentType = ApiEndpoint.IMAGE,
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/apps",
            setOf(Scope.OBSERVE),
            "List installed apps.",
            response = ListSerializer(serializer<AppInfo>()),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/apps/{pkg}/launch",
            setOf(Scope.CONTROL),
            "Launch an app.",
            serializer<LaunchRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/apps/{pkg}/stop",
            setOf(Scope.CONTROL),
            "Force-stop an app. Requires the helper.",
            response = serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/apps/{pkg}/clear",
            setOf(Scope.CONTROL),
            "Clear an app's data. Requires the helper.",
            response = serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/intents",
            setOf(Scope.CONTROL),
            "Start an intent. Requires the helper.",
            serializer<IntentRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/wait",
            setOf(Scope.OBSERVE, Scope.BROWSER),
            "Wait until conditions match. browser.* conditions need the browser scope. Other conditions, snapshot, and settleMs need observe. A wait with only browser.* conditions needs only browser, and its result leaves out state.",
            serializer<WaitRequest>(),
            serializer<WaitResult>(),
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/browser/targets",
            setOf(Scope.BROWSER),
            "List Chrome tabs and debuggable WebViews.",
            response = ListSerializer(serializer<BrowserTarget>()),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/tabs",
            setOf(Scope.BROWSER),
            "Open a Chrome tab.",
            serializer<OpenTabRequest>(),
            serializer<BrowserTarget>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/navigate",
            setOf(Scope.BROWSER),
            "Navigate a browser target.",
            serializer<NavigateRequest>(),
            serializer<BrowserTarget>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/close",
            setOf(Scope.BROWSER),
            "Close a browser tab without bringing it forward.",
            response = serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/browser/targets/{id}/snapshot",
            setOf(Scope.BROWSER),
            "Compact outline of a browser target. Query maxNodes (default 400) and maxChars cap it, and show brings the tab on screen first.",
            response = serializer<BrowserSnapshot>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/read",
            setOf(Scope.BROWSER),
            "Read a browser target's text as markdown or plain text, a slice at a time, or its outline.",
            serializer<BrowserReadRequest>(),
            serializer<BrowserReadResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/find",
            setOf(Scope.BROWSER),
            "Find text or links in a browser target's content, at the offsets read uses.",
            serializer<BrowserFindRequest>(),
            serializer<BrowserFindResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/tap",
            setOf(Scope.BROWSER),
            "Tap a ref or CSS selector. input touch (default) brings the tab forward; input cdp needs the tab showing in Chrome on screen; input cdp-mouse clicks a background tab, even with Chrome hidden.",
            serializer<BrowserTapRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/swipe",
            setOf(Scope.BROWSER),
            "Swipe in CSS viewport pixels, or by direction. input touch (default) or cdp, which needs the tab showing in Chrome on screen.",
            serializer<BrowserSwipeRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/gesture",
            setOf(Scope.BROWSER),
            "Pointer paths in CSS viewport pixels. input touch (default) or cdp, which needs the tab showing in Chrome on screen.",
            serializer<BrowserGestureRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/key",
            setOf(Scope.BROWSER),
            "Press a key. input touch (default) or cdp. cdp rejects device keys such as HOME.",
            serializer<BrowserKeyRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/text",
            setOf(Scope.BROWSER),
            "Type text. input touch uses the hardware text modes; input cdp inserts it.",
            serializer<BrowserTextRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/evaluate",
            setOf(Scope.BROWSER),
            "Evaluate JavaScript in an isolated world.",
            serializer<EvalRequest>(),
            serializer<EvalResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/capture",
            setOf(Scope.BROWSER),
            "Start or stop recording a tab's JSON responses for scope data.",
            serializer<BrowserCaptureRequest>(),
            serializer<BrowserCaptureResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/console",
            setOf(Scope.BROWSER),
            "Collect console entries.",
            serializer<ConsoleRequest>(),
            serializer<ConsoleResult>(),
        ),
        endpoint(
            ApiMethod.WEBSOCKET,
            "/v1/events",
            setOf(Scope.OBSERVE),
            "Stream device events as JSON text frames. Logcat is included only when types contains logcat.",
            response = serializer<Event>(),
        ),
        endpoint(
            ApiMethod.GET,
            "/viewer",
            setOf(Scope.STREAM),
            "WebCodecs viewer page.",
            responseContentType = ApiEndpoint.HTML,
        ),
        endpoint(
            ApiMethod.WEBSOCKET,
            "/v1/stream/video",
            setOf(Scope.STREAM),
            "H.264 video stream.",
        ),
        endpoint(
            ApiMethod.WEBSOCKET,
            "/v1/stream/audio",
            setOf(Scope.STREAM),
            "Opus or AAC audio stream.",
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/tokens",
            setOf(Scope.ADMIN),
            "List bearer tokens.",
            response = ListSerializer(serializer<TokenInfo>()),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/tokens",
            setOf(Scope.ADMIN),
            "Create a bearer token.",
            serializer<CreateTokenRequest>(),
            serializer<CreatedToken>(),
            status = 201,
        ),
        endpoint(
            ApiMethod.PATCH,
            "/v1/tokens/{id}",
            setOf(Scope.ADMIN),
            "Replace a token's scopes. Its MCP sessions that used a scope it lost end at their next request, or within 30 s.",
            serializer<UpdateTokenRequest>(),
            serializer<TokenInfo>(),
        ),
        endpoint(
            ApiMethod.DELETE,
            "/v1/tokens/{id}",
            setOf(Scope.ADMIN),
            "Revoke a bearer token.",
            status = 204,
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/sessions",
            emptySet(),
            "Start a session. REST calls that act on the phone or its tabs need its id in Mcp-Session-Id, and fail with 428 session_required without one. It holds the screen across calls and owns the tabs it opens, as an MCP session does.",
            response = serializer<SessionStarted>(),
            status = 201,
        ),
        endpoint(
            ApiMethod.DELETE,
            "/v1/sessions/current",
            emptySet(),
            "End the session named in Mcp-Session-Id: cleanup if it held the screen, and the tabs it opened close.",
            status = 204,
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/screen/release",
            emptySet(),
            "Let the screen go for the session named in Mcp-Session-Id, with cleanup. The session keeps its tabs, and its next call that needs the screen takes it again.",
            response = serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/sessions",
            setOf(Scope.ADMIN),
            "List MCP sessions by a hash of their id, with their age, idle time, and whether they hold the screen.",
            response = ListSerializer(serializer<SessionInfo>()),
        ),
        endpoint(
            ApiMethod.DELETE,
            "/v1/sessions/{hash}",
            setOf(Scope.ADMIN),
            "End an MCP session, as its own DELETE would.",
            status = 204,
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/openapi.json",
            setOf(Scope.OBSERVE),
            "OpenAPI 3.1 document for this API.",
            response = serializer<JsonObject>(),
        ),
    )

private fun endpoint(
    method: ApiMethod,
    path: String,
    scopes: Set<Scope>,
    summary: String,
    request: KSerializer<*>? = null,
    response: KSerializer<*>? = null,
    status: Int = if (method == ApiMethod.DELETE && response == null) 204 else 200,
    responseContentType: String? = null,
    requestContentType: String? = null,
): ApiEndpoint =
    ApiEndpoint(
        method = method,
        path = path,
        scopes = scopes,
        summary = summary,
        request = request,
        response = response,
        status = status,
        responseContentType = responseContentType ?: response?.let { ApiEndpoint.JSON },
        requestContentType = requestContentType,
    )
