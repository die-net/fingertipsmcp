package com.fingertipsmcp.mcp

import com.fingertipsmcp.browser.touchAllowed
import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.lease.ScreenLease
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserInput
import com.fingertipsmcp.model.BrowserInputKind
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.WaitCondition
import com.fingertipsmcp.server.BrowserInputPolicy
import com.fingertipsmcp.wait.usesScreen
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * How a tool uses the screen, as docs/sharing.md lists. Every tool in the catalog declares one, so
 * a new tool cannot be left out. Reading needs no screen. Browser input takes it when the product's
 * [BrowserInputPolicy] says its input does: on Android, touch input brings the tab forward, and
 * Chrome only takes cdp pointer input on the tab it is showing.
 */
internal class ToolScreen(
    /** Whether every call takes the screen, so its description says so. */
    val always: Boolean = false,
    /** Whether a call with these arguments takes the screen. */
    private val byArguments: (JsonObject?) -> Boolean = { always },
    /** For browser input, its kind, which the product's policy decides the screen for. */
    private val input: BrowserInputKind? = null,
    /** Whether a call with these arguments opens a tab, which starts Chrome when it is stopped. */
    val opensTab: (JsonObject?) -> Boolean = { false },
    /** Whether a call needs the screen only while it runs, so it gives back a screen it took. */
    val brief: Boolean = false,
    /**
     * Whether a call with these arguments, from a session without the screen, acts on a page that
     * could launch an app, so the foreground watch watches the top task around it. Opening a page
     * is such an act.
     */
    private val pageActs: (JsonObject?) -> Boolean = { false },
) {
    fun takes(args: JsonObject?, policy: BrowserInputPolicy): Boolean =
        always || byArguments(args) || input?.let { policy.takesScreen(inputOf(args), it) } == true

    fun launches(args: JsonObject?): Boolean = pageActs(args) || opensTab(args)

    companion object {
        /** A tool that acts on the screen or shows what is on it. */
        val ALWAYS = ToolScreen(always = true)

        /** A tool that never takes the screen and acts on no page. */
        val NEVER = ToolScreen()

        /** Browser input of [kind], which acts on a page. */
        fun input(kind: BrowserInputKind) = ToolScreen(input = kind, pageActs = { true })

        /** Acts on a page without the screen, such as by navigating it. */
        val PAGE = ToolScreen(pageActs = { true })

        /** A look at a tab, which takes the screen with `show`. */
        val SHOWN = ToolScreen(byArguments = { flag(it, "show") })

        /**
         * A read or find, which takes the screen with `show`, and with `url` opens a tab and the
         * page in it.
         */
        val READ = ToolScreen(byArguments = { flag(it, "show") }, opensTab = ::hasUrl)

        /** Opens a tab, taking the screen for an incognito one, only while it opens. */
        val OPEN =
            ToolScreen(byArguments = { flag(it, "incognito") }, opensTab = { true }, brief = true)

        /** wait_for, which takes the screen for conditions on the UI and for its pictures. */
        val WAIT =
            ToolScreen(
                byArguments = {
                    conditionsUseScreen(it) || flag(it, "snapshot") || flag(it, "screenshot")
                }
            )
    }
}

private fun flag(args: JsonObject?, name: String): Boolean =
    (args?.get(name) as? JsonPrimitive)?.booleanOrNull == true

private fun hasUrl(args: JsonObject?): Boolean =
    (args?.get("url") as? JsonPrimitive)?.contentOrNull != null

/** Whether a call takes the screen, and whether it takes it only while it runs. */
internal data class ScreenUse(val needed: Boolean, val brief: Boolean)

/**
 * How a call with [args] to a tool that uses the screen as [screen] says does. Its arguments
 * decide, except that opening a tab while [browser] has Chrome stopped starts Chrome, which brings
 * it forward, so that call takes the screen while the tab opens.
 */
internal suspend fun screenUse(
    screen: ToolScreen,
    args: JsonObject?,
    browser: BrowserService,
    policy: BrowserInputPolicy,
): ScreenUse {
    val byArguments = screen.takes(args, policy)
    val startsChrome = screen.opensTab(args) && browser.chromeStopped()
    return ScreenUse(
        needed = byArguments || startsChrome,
        brief = screen.brief || (startsChrome && !byArguments),
    )
}

/** The browser input tools, and the kind of input each sends. */
private val INPUT_KINDS =
    mapOf(
        "browser_tap" to BrowserInputKind.TAP,
        "browser_swipe" to BrowserInputKind.POINTER,
        "browser_gesture" to BrowserInputKind.POINTER,
        "browser_key" to BrowserInputKind.KEY,
        "browser_type" to BrowserInputKind.TEXT,
    )

/**
 * Replaces the tool's `input` with what [policy] makes of it for a caller that [holdsScreen] or
 * not, so [takesScreen] and the tool see the input that will be used. A token whose [scopes] may
 * not send the tool's input as touch resolves as if it did not hold the screen. An input that does
 * not parse is left for the tool to refuse.
 */
internal fun resolveInput(
    tool: String,
    request: CallToolRequest,
    holdsScreen: Boolean,
    scopes: Set<Scope>,
    policy: BrowserInputPolicy,
): CallToolRequest {
    val kind = INPUT_KINDS[tool] ?: return request
    val args = request.params.arguments ?: JsonObject(emptyMap())
    val asked = inputOrNull(args) ?: return request
    val resolved = policy.resolve(asked, kind, holdsScreen && touchAllowed(kind, scopes))
    if (resolved == asked && args["input"] != null) return request
    val wire = ApiJson.encodeToJsonElement(BrowserInput.serializer(), resolved)
    val params = request.params.copy(arguments = JsonObject(args + ("input" to wire)))
    return request.copy(params = params)
}

/** The input [args] ask for: `auto` when they leave it out, null when it does not parse. */
private fun inputOrNull(args: JsonObject?): BrowserInput? {
    val value = (args?.get("input") as? JsonPrimitive)?.contentOrNull ?: return BrowserInput.AUTO
    return BrowserInput.entries.firstOrNull { wire(it) == value.lowercase() }
}

/** The input [args] ask for, with one that does not parse counted as `auto`. */
private fun inputOf(args: JsonObject?): BrowserInput = inputOrNull(args) ?: BrowserInput.AUTO

private fun wire(input: BrowserInput): String =
    ApiJson.encodeToJsonElement(BrowserInput.serializer(), input).let {
        (it as JsonPrimitive).content
    }

/**
 * Whether any condition in wait_for's [args] uses the screen. One that does not parse counts as
 * using it, as an unknown type would; the call itself then reports the error.
 */
private fun conditionsUseScreen(args: JsonObject?): Boolean =
    listOf("all", "any").any { key ->
        (args?.get(key) as? JsonArray).orEmpty().any { condition ->
            runCatching { ApiJson.decodeFromJsonElement(WaitCondition.serializer(), condition) }
                .map(::usesScreen)
                .getOrDefault(true)
        }
    }

/** `took_screen` as a tool error, like the lease's own errors. */
internal suspend fun tookScreenError(call: suspend () -> CallToolResult): CallToolResult =
    try {
        call()
    } catch (e: ApiException) {
        if (e.error != "took_screen") throw e
        errorResult(e)
    }

private fun errorResult(e: ApiException) =
    CallToolResult(
        content = listOf(TextContent(text = "${e.error}: ${e.message.orEmpty()}")),
        isError = true,
    )

/**
 * Runs [call] holding the screen for [holder] when [needed], and puts the holder's notes first in
 * the result. With [giveBack], a holder that did not have the screen lets it go after the call.
 * `screen_busy` and `screen_taken` come back as tool errors.
 */
internal suspend fun leased(
    lease: ScreenLease,
    holder: Holder,
    needed: Boolean,
    giveBack: Boolean = false,
    call: suspend () -> CallToolResult,
): CallToolResult {
    // A call that needs no screen still shows the notes waiting for its session, such as the
    // screen having gone to another client, so they are not held until its next screen call.
    if (!needed) return withNotes(call(), lease.takeNotes(holder))
    val screened =
        try {
            lease.withScreen(holder, giveBack) { call() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            return errorResult(e)
        }
    return withNotes(screened.value, screened.notes)
}

/** [result] with [notes] first, as `# note:` lines. */
private fun withNotes(result: CallToolResult, notes: List<String>): CallToolResult {
    if (notes.isEmpty()) return result
    val lines = TextContent(text = notes.joinToString("\n") { "# note: $it" })
    return CallToolResult(
        content = listOf(lines) + result.content,
        isError = result.isError,
        structuredContent = result.structuredContent,
    )
}
