package net.die.phoneapi.browser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.EvalResult

/**
 * Evaluates [expression] in an isolated world on the page's main frame, so the page's own
 * JavaScript neither sees nor changes it. A script exception comes back as [EvalResult.exception].
 */
internal suspend fun evaluateIn(
    cdp: CdpSession,
    expression: String,
    awaitPromise: Boolean,
): EvalResult = evaluateInFrame(cdp, mainFrameId(cdp), expression, awaitPromise)

/** The id of the page's main frame. */
internal suspend fun mainFrameId(cdp: CdpSession): String = mainFrame(cdp).id

/** The page's main frame, with its id, URL, and loader id. */
internal suspend fun mainFrame(cdp: CdpSession): MainFrame {
    val frame = cdp.call("Page.getFrameTree").decodeCdp<FrameTreeResult>()?.frameTree?.frame
    val id = frame?.id ?: throw ApiException(502, "cdp_error", "Chrome did not return a frame")
    return MainFrame(id, frame.url, frame.loaderId)
}

/** A page's main frame. [loaderId] names the document loaded in it, and changes with each load. */
internal data class MainFrame(val id: String, val url: String?, val loaderId: String? = null)

/**
 * [evaluateIn] for a caller that already has the main frame's id. A world belongs to one document,
 * so when the page loads a new one between making the world and running in it, Chrome no longer has
 * the world and [expression] has not run. It then runs once more, in a world on the new document.
 */
internal suspend fun evaluateInFrame(
    cdp: CdpSession,
    frameId: String,
    expression: String,
    awaitPromise: Boolean,
): EvalResult {
    try {
        return evaluateInWorld(cdp, isolatedWorld(cdp, frameId), expression, awaitPromise)
    } catch (e: ApiException) {
        if (!worldGone(e)) throw e
    }
    try {
        return evaluateInWorld(cdp, isolatedWorld(cdp, frameId), expression, awaitPromise)
    } catch (e: ApiException) {
        if (!worldGone(e)) throw e
        throw ApiException(
            502,
            "cdp_error",
            "The page loaded a new document while it was read, twice. Wait for it to settle, " +
                "then try again.",
            cause = e,
        )
    }
}

/**
 * Whether Chrome refused to run in a world because its document has gone. Chrome checks this before
 * running anything, so the expression did not run. A broken connection is no such refusal, whatever
 * it says. The code is the one Chrome gives any server error, so the message is the only signal
 * that names this one, as docs/device-notes.md records.
 */
private fun worldGone(e: ApiException): Boolean =
    e.rejected?.let { it.code == SERVER_ERROR && it.message == NO_CONTEXT } == true

/** A new isolated world on the page's main frame, by its execution context id. */
internal suspend fun isolatedWorld(cdp: CdpSession): Int = isolatedWorld(cdp, mainFrameId(cdp))

private suspend fun isolatedWorld(cdp: CdpSession, frameId: String): Int =
    cdp.call(
            "Page.createIsolatedWorld",
            buildJsonObject {
                put("frameId", frameId)
                put("worldName", ISOLATED_WORLD)
            },
        )
        .decodeCdp<WorldResult>()
        ?.executionContextId
        ?: throw ApiException(502, "cdp_error", "Chrome did not return an isolated world")

/** The object id `DOM.resolveNode` gives for the node [params] name, or null if it gives none. */
internal suspend fun resolvedNode(cdp: CdpSession, params: JsonObject): String? =
    cdp.call("DOM.resolveNode", params).decodeCdp<Resolved>()?.`object`?.objectId

/**
 * Runs [body], the statements of a function, in the world [objectId] was resolved in, with that
 * object as `this`. Script in a document's own world leaves globals behind, so an object from there
 * is only for a document with no site script to see them, such as the page [PdfReader] makes.
 * Chrome runs it in the object's document or not at all, so a document loaded in its place never
 * runs it.
 */
internal suspend fun callOn(
    cdp: CdpSession,
    objectId: String,
    body: String,
    awaitPromise: Boolean,
): EvalResult =
    evalResult(
        cdp.call(
            "Runtime.callFunctionOn",
            buildJsonObject {
                put("objectId", objectId)
                put("functionDeclaration", "function () {\n$body\n}")
                put("returnByValue", true)
                put("awaitPromise", awaitPromise)
            },
        )
    )

/** Evaluates [expression] in the isolated world [contextId]. */
internal suspend fun evaluateInWorld(
    cdp: CdpSession,
    contextId: Int,
    expression: String,
    awaitPromise: Boolean,
): EvalResult {
    return evalResult(
        cdp.call(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", expression)
                put("contextId", contextId)
                put("returnByValue", true)
                put("awaitPromise", awaitPromise)
            },
        )
    )
}

private fun evalResult(payload: JsonObject): EvalResult {
    val parsed = payload.decodeCdp<EvalPayload>() ?: return EvalResult()
    val details = parsed.exceptionDetails
    if (details != null) return EvalResult(exception = exceptionMessage(details))
    val remote = parsed.result ?: return EvalResult()
    return EvalResult(type = remote.type, value = remote.value)
}

private fun exceptionMessage(details: ExceptionText): String {
    val description = details.exception?.description
    val described = description?.lineSequence()?.firstOrNull()?.trim()
    if (!described.isNullOrEmpty()) return described
    val value = details.exception?.value
    if (value is JsonPrimitive && value.isString) return value.content
    return details.text ?: "exception"
}

@Serializable private data class WorldResult(val executionContextId: Int? = null)

@Serializable private data class Resolved(val `object`: Handle? = null)

@Serializable private data class Handle(val objectId: String? = null)

@Serializable
private data class EvalPayload(
    val result: RemoteObject? = null,
    val exceptionDetails: ExceptionText? = null,
)

@Serializable
private data class RemoteObject(val type: String? = null, val value: JsonElement? = null)

@Serializable
private data class ExceptionText(
    val text: String? = null,
    val exception: ExceptionObject? = null,
)

@Serializable
private data class ExceptionObject(
    val description: String? = null,
    val value: JsonElement? = null,
)

private const val ISOLATED_WORLD = "phoneapi"

/** Chrome's error when a Runtime call names an execution context that no longer exists. */
private const val NO_CONTEXT = "Cannot find context with specified id"
