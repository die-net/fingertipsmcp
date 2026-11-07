package net.die.phoneapi.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import net.die.phoneapi.browser.requireTouchAccess
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.core.ScreenshotScale
import net.die.phoneapi.core.checkLogTag
import net.die.phoneapi.core.windowId
import net.die.phoneapi.helperclient.failureMessage
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserCaptureRequest
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserInputKind
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserSnapshotOptions
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.ClipboardReadRequest
import net.die.phoneapi.model.ClipboardWriteRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.LaunchRequest
import net.die.phoneapi.model.NavigateRequest
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.NodeRefArg
import net.die.phoneapi.model.OpenTabRequest
import net.die.phoneapi.model.OrientationRequest
import net.die.phoneapi.model.PackageArg
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.ScreenReleaseRequest
import net.die.phoneapi.model.SnapshotFormat
import net.die.phoneapi.model.SnapshotOptions
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TargetArg
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.UnlockRequest
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.schema.Doc
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.routes.releaseScreen
import net.die.phoneapi.tabs.withTarget
import net.die.phoneapi.wait.requireWaitAccess

internal suspend fun deviceInfo(services: ServerServices): CallToolResult = runTool {
    val info = services.device.info()
    val caller = services.holder
    val control = services.sessions
    val state = services.lease.state.value
    jsonText(
        if (caller != null && control != null) control.withScreenHolder(info, state, caller)
        else info
    )
}

internal suspend fun uiSnapshot(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<SnapshotArgs>()
    val snapshot =
        services.ui.snapshot(
            SnapshotOptions(
                format = SnapshotFormat.COMPACT,
                windowId = args.window?.let(::windowId),
                maxDepth = args.maxDepth,
                maxNodes = args.maxNodes,
                maxChars = args.maxChars,
                textOnly = args.textOnly,
                redact = args.redact,
                includeInvisible = args.includeInvisible,
                allWindows = args.all,
                autoWake = args.autoWake,
            )
        )
    snapshot.compact ?: "(empty snapshot)"
}

internal suspend fun uiFind(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.ui.find(request.args<FindRequest>()))
    }

internal suspend fun uiAct(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        val ref = request.args<NodeRefArg>().ref
        jsonText(services.ui.act(ref, request.args<NodeActionRequest>()))
    }

internal suspend fun tap(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.input.tap(request.args<TapRequest>()))
    }

internal suspend fun swipe(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.input.swipe(request.args<SwipeRequest>()))
    }

internal suspend fun typeText(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.input.text(request.args<TextRequest>()))
    }

internal suspend fun pressKey(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.input.key(request.args<KeyRequest>()))
    }

internal suspend fun clipboardRead(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runContent {
    val clip = services.clipboard.read(request.args<ClipboardReadRequest>())
    val image =
        if (clip.text == null && clip.mimeTypes.any { it.startsWith("image/") }) {
            services.clipboard.image()
        } else {
            null
        }
    when {
        image == null -> listOf(TextContent(text = jsonText(clip)))
        image.bytes.size > MAX_IMAGE_BYTES ->
            listOf(
                TextContent(text = jsonText(clip)),
                TextContent(
                    text =
                        "The image is ${image.bytes.size} bytes, too large to return here. " +
                            "GET /v1/clipboard/image returns it."
                ),
            )
        else ->
            listOf(
                TextContent(text = jsonText(clip)),
                ImageContent(
                    data = Base64.getEncoder().encodeToString(image.bytes),
                    mimeType = image.mimeType,
                ),
            )
    }
}

internal suspend fun clipboardWrite(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.clipboard.write(request.args<ClipboardWriteRequest>()))
}

internal suspend fun waitFor(
    services: ServerServices,
    scopes: Set<Scope>,
    request: CallToolRequest,
): CallToolResult = runContent {
    val args = request.args<WaitArgs>()
    val wait =
        WaitRequest(
            all = args.all.map { withTarget(it, args.target) },
            any = args.any.map { withTarget(it, args.target) },
            timeoutMs = args.timeoutMs,
            settleMs = args.settleMs,
            snapshot = args.snapshot,
            snapshotFormat = SnapshotFormat.COMPACT,
            snapshotOptions =
                SnapshotOptions(
                    windowId = args.window?.let(::windowId),
                    maxNodes = args.maxNodes,
                    maxChars = args.maxChars,
                    textOnly = args.textOnly,
                    redact = args.redact,
                ),
        )
    requireWaitAccess(wait, scopes)
    if (args.screenshot && Scope.OBSERVE !in scopes) throw ApiException.forbidden(Scope.OBSERVE)
    val result = services.waits.wait(wait, scopes)
    val header =
        "matched=${result.matched} timedOut=${result.timedOut} elapsedMs=${result.elapsedMs}"
    val body = result.snapshot?.compact
    val text = TextContent(text = if (body.isNullOrBlank()) header else "$header\n$body")
    if (args.screenshot) listOf(text, screenshotContent(services, args.scale)) else listOf(text)
}

internal suspend fun sessionStart(services: ServerServices): CallToolResult = runTool {
    val id = checkNotNull(services.sessions).start()
    "session=$id\nPass session=$id on every phone call for this task, reads included. A call " +
        "without it acts as you, and tabs opened without it are yours, not the task's. End it " +
        "with session_end when the task is done."
}

internal suspend fun sessionEnd(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val id = request.args<SessionEndArgs>().session
    if (!checkNotNull(services.sessions).end(id)) {
        throw ApiException.notFound("No session you started has that id; it may have ended")
    }
    "Ended the session: its tabs are closed, and the screen is cleaned up if it held it."
}

/** Lets the session's screen go, keeping the session and its tabs. */
internal suspend fun screenRelease(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val holder = services.holder ?: return@runTool "This call has no MCP session."
    releaseScreen(services, holder, request.args<ScreenReleaseRequest>())
}

internal suspend fun keyboardHide(services: ServerServices): CallToolResult = runTool {
    jsonText(services.input.hideIme())
}

internal suspend fun keyboardShow(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.input.showIme(request.args<ImeShowRequest>()))
}

internal suspend fun appLaunch(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        val pkg = request.args<PackageArg>().packageName
        jsonText(services.apps.launch(pkg, request.args<LaunchRequest>()))
    }

internal suspend fun appStop(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.apps.stop(request.args<PackageArg>().packageName))
    }

internal suspend fun appClear(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.apps.clear(request.args<PackageArg>().packageName))
    }

internal suspend fun openIntent(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val intent = request.args<IntentRequest>()
    jsonText(services.tabs.intent(checkNotNull(services.holder)) { services.apps.intent(intent) })
}

internal suspend fun deviceWake(services: ServerServices): CallToolResult = runTool {
    jsonText(services.power.wake())
}

internal suspend fun deviceUnlock(services: ServerServices): CallToolResult = runTool {
    jsonText(services.power.unlock(UnlockRequest()))
}

internal suspend fun deviceLock(services: ServerServices): CallToolResult = runTool {
    jsonText(services.power.lock())
}

internal suspend fun deviceOrientation(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.power.orientation(request.args<OrientationRequest>()))
}

internal suspend fun browserTargets(services: ServerServices): CallToolResult = runTool {
    jsonText(services.browser.targets())
}

internal suspend fun browserOpen(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val open = request.args<OpenTabRequest>()
    jsonText(services.browser.openTab(open.url, open.capture, open.incognito))
}

internal suspend fun browserCapture(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.browser.capture(request.target(), request.args<BrowserCaptureRequest>().on))
}

internal suspend fun browserNavigate(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.browser.navigate(request.target(), request.args<NavigateRequest>().url))
}

internal suspend fun browserClose(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.browser.closeTab(request.target()))
}

internal suspend fun browserSnapshot(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val options = request.args<BrowserSnapshotOptions>()
    snapshotText(services.browser.snapshot(request.target(), options), options.show)
}

/** The outline, with notes on whether the tab was drawn after its header line. */
internal fun snapshotText(snapshot: BrowserSnapshot, show: Boolean = false): String {
    val notes =
        listOfNotNull(RELOADED_NOTE.takeIf { snapshot.reloaded }) +
            drawnNotes(snapshot.shown, snapshot.pending, sparse = false, show, "snapshot")
    if (notes.isEmpty()) return snapshot.compact
    val header = snapshot.compact.substringBefore('\n')
    val rest = snapshot.compact.substringAfter('\n', "")
    return (listOf(header) + notes + listOfNotNull(rest.ifEmpty { null })).joinToString("\n")
}

internal suspend fun browserTap(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.browser.tap(request.target(), request.args<BrowserTapRequest>()))
}

internal suspend fun browserSwipe(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.browser.swipe(request.target(), request.args<BrowserSwipeRequest>()))
}

internal suspend fun browserGesture(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.browser.gesture(request.target(), request.args<BrowserGestureRequest>()))
}

internal suspend fun browserKey(
    services: ServerServices,
    scopes: Set<Scope>,
    request: CallToolRequest,
): CallToolResult = runTool {
    val key = request.args<BrowserKeyRequest>()
    requireTouchAccess(BrowserInputKind.KEY, key.input, scopes)
    jsonText(services.browser.key(request.target(), key))
}

internal suspend fun browserText(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.browser.text(request.target(), request.args<BrowserTextRequest>()))
}

internal suspend fun browserEval(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.browser.evaluate(request.target(), request.args<EvalRequest>()))
}

internal suspend fun browserConsole(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.browser.console(request.target(), request.args<ConsoleRequest>()))
}

internal suspend fun screenshot(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runContent {
    listOf(screenshotContent(services, request.args<ScaleArgs>().scale))
}

private suspend fun screenshotContent(services: ServerServices, scale: Float): ImageContent {
    val png = services.screenshots(scale)
    if (png.size > MAX_IMAGE_BYTES) {
        throw ApiException.badRequest("Screenshot is too large; pass a smaller scale")
    }
    return ImageContent(data = Base64.getEncoder().encodeToString(png), mimeType = "image/png")
}

internal suspend fun logcatTail(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<LogcatArgs>()
    if (args.lines !in 1..MAX_LOG_LINES) {
        throw ApiException.badRequest("lines must be 1..$MAX_LOG_LINES")
    }
    val tag = args.tag
    if (tag != null) checkLogTag(tag, "tag")
    // `-t N` counts raw buffer lines, then the tag filter runs, so a tag plus a small N
    // returns nothing. Dump the tag and keep the last N matching lines instead.
    val argv =
        if (tag == null) {
            listOf("logcat", "-d", "-t", args.lines.toString())
        } else {
            listOf("logcat", "-d", "-s", "$tag:I")
        }
    val result = services.shell(argv)
    if (!result.ok) {
        val detail = result.failureMessage().ifBlank { "logcat exited ${result.exit}" }
        throw ApiException.unavailable("helper_error", detail)
    }
    if (tag == null) result.stdout else tailLines(result.stdout, args.lines)
}

private fun tailLines(stdout: String, lines: Int): String =
    stdout
        .lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("---------") }
        .toList()
        .takeLast(lines)
        .joinToString("\n")

internal suspend fun runTool(block: suspend () -> String): CallToolResult = runContent {
    listOf(TextContent(text = block()))
}

/** Runs [block], turning API and argument errors into tool errors. */
internal suspend fun runContent(block: suspend () -> List<ContentBlock>): CallToolResult =
    try {
        CallToolResult(content = block(), isError = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        toolFailure(toolError(e))
    } catch (e: SerializationException) {
        toolFailure("bad_request: ${e.message.orEmpty()}")
    }

private fun toolFailure(text: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(text = text)), isError = true)

private fun toolError(error: ApiException): String {
    val detail = error.message?.takeIf { it.isNotBlank() && it != error.error }
    return if (detail == null) error.error else "${error.error}: $detail"
}

internal inline fun <reified T> jsonText(value: T): String =
    ApiJson.encodeToString(serializer<T>(), value)

/**
 * The arguments as [T], which decodes the fields it names and ignores the rest, since a tool's
 * arguments can be several types' fields together (see [McpTool.arguments]).
 */
internal inline fun <reified T> CallToolRequest.args(): T {
    val arguments = this.arguments ?: JsonObject(emptyMap())
    return ApiJson.decodeFromJsonElement(serializer<T>(), arguments)
}

/** The browser tab the call names. */
internal fun CallToolRequest.target(): String = args<TargetArg>().target

@Serializable
internal data class SnapshotArgs(
    @Doc("Show only this window, such as 3 or w3. Omit it for the default set of windows.")
    val window: String? = null,
    @Doc("Stop this many levels down. Leaving nodes out marks the outline truncated.")
    val maxDepth: Int? = null,
    @Doc(
        "Most nodes to show. Defaults to 400. The rest are counted, and a line after the header says so."
    )
    val maxNodes: Int = DEFAULT_SNAPSHOT_NODES,
    @Doc("Most characters of outline to show, cut at a line. Cuts are reported like maxNodes.")
    val maxChars: Int? = null,
    @Doc("Leave out positions, resource ids, states, and actions, for reading rather than acting.")
    val textOnly: Boolean = false,
    @Doc(
        "Mask the text of password and one-time-code fields, and anything that looks like a card number. The first line then counts them as redacted=N. Defaults to true. A screenshot still shows them."
    )
    val redact: Boolean = true,
    @Doc("Also include nodes that are off screen or hidden.") val includeInvisible: Boolean = false,
    @Doc("Include every window, such as the status bar and the keyboard's contents.")
    val all: Boolean = false,
    val autoWake: Boolean = true,
)

@Serializable
internal data class ScaleArgs(
    @Doc("Scale from 0.1 to 1. Defaults to 0.5, which is smaller than the REST default.")
    val scale: Float = ScreenshotScale.MCP
)

@Serializable internal data class LogcatArgs(val lines: Int = 80, val tag: String? = null)

@Serializable internal class EmptyArgs

@Serializable
internal data class SessionEndArgs(@Doc("The id session_start returned.") val session: String)

@Serializable
internal data class WaitArgs(
    @Doc(
        "Every condition must match. Each object's type is node, window, ime, idle, screen, keyguard, or browser.*. all and any together need at least one condition."
    )
    val all: List<WaitCondition> = emptyList(),
    @Doc("At least one condition must match. Same types as all.")
    val any: List<WaitCondition> = emptyList(),
    @Doc(
        "The browser tab for every browser.* condition that names none, so it need not be repeated on each."
    )
    val target: String? = null,
    @Doc("Give up after this many milliseconds.") val timeoutMs: Long = 10_000,
    @Doc("After a match, keep waiting until the UI is quiet for this many milliseconds.")
    val settleMs: Long = 0,
    @Doc(
        "Include a fresh ui_snapshot outline in the result, so one call both waits for an action's result and shows it. Cut and masked as ui_snapshot is, by the arguments below."
    )
    val snapshot: Boolean = false,
    @Doc(
        "Include a screenshot in the result, for maps, Street View, photos, and other screens whose tree is empty."
    )
    val screenshot: Boolean = false,
    @Doc("Screenshot scale from 0.1 to 1. Defaults to 0.5.") val scale: Float = ScreenshotScale.MCP,
    @Doc("Snapshot only this window, such as 3 or w3.") val window: String? = null,
    @Doc("Most snapshot nodes to show. Defaults to 400. A line after the header counts the rest.")
    val maxNodes: Int = DEFAULT_SNAPSHOT_NODES,
    @Doc("Most characters of snapshot to show, cut at a line.") val maxChars: Int? = null,
    @Doc("Leave positions, resource ids, states, and actions out of the snapshot.")
    val textOnly: Boolean = false,
    @Doc("Mask secrets in the snapshot as ui_snapshot does. Defaults to true.")
    val redact: Boolean = true,
)

/** The most nodes an MCP snapshot shows unless asked for more, as for browser_snapshot. */
private const val DEFAULT_SNAPSHOT_NODES = 400
private const val MAX_IMAGE_BYTES = 1_500_000
private const val MAX_LOG_LINES = 400
