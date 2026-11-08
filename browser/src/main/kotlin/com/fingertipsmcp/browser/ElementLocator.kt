package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Finds the element a tap or focus names on a page: a snapshot ref, through the target's last
 * accessibility tree in [axTrees], or a CSS selector. A ref holds only on the document its snapshot
 * read, so one from another document, or whose node has gone, is `stale_ref`, never looked up again
 * by an id that may name another element now. A node still on the page with no box is `offscreen`.
 */
internal class ElementLocator(private val axTrees: AxTreeCache) {
    /**
     * The content quads of [target], in CSS viewport pixels. With [scroll], Chrome scrolls it into
     * view first; without, they can lie outside the viewport, for a caller that scrolls as a person
     * would.
     */
    suspend fun quads(
        cdp: CdpSession,
        targetId: String,
        target: TapTarget,
        scroll: Boolean = true,
    ): JsonArray =
        when (target) {
            is TapTarget.Ref -> refQuads(cdp, targetId, target.id, scroll)
            is TapTarget.Css -> cssQuads(cdp, target, scroll)
        }

    /**
     * Polls until [target] matches an element with a box, or its wait is up. Then the last reason
     * it did not, no match or no box, is the failure.
     */
    private suspend fun cssQuads(
        cdp: CdpSession,
        target: TapTarget.Css,
        scroll: Boolean,
    ): JsonArray {
        var why: ApiException? = null
        suspend fun attempt(): JsonArray? =
            try {
                cdp.withNodeIds { boxed(cdp, cssNode(cdp, target.selector), scroll) }
            } catch (e: ApiException) {
                if (e.error != "not_found" && e.error != "offscreen") throw e
                why = e
                null
            }
        attempt()?.let {
            return it
        }
        return withTimeoutOrNull(target.waitMs) {
            var quads: JsonArray? = null
            while (quads == null) {
                delay(ELEMENT_POLL_MS)
                quads = attempt()
            }
            quads
        } ?: throw checkNotNull(why)
    }

    private suspend fun refQuads(
        cdp: CdpSession,
        targetId: String,
        ref: String,
        scroll: Boolean,
    ): JsonArray {
        val params = refParams(cdp, targetId, ref)
        try {
            return boxed(cdp, params, scroll)
        } catch (e: ApiException) {
            if (e.rejected == null || inDocument(cdp, params)) throw e
            throw staleRef(ref, "its node has left the page", e)
        }
    }

    /**
     * Whether the node [params] names is still in its document. Chrome keeps answering for a
     * backend node id after its node leaves the page, and refuses the box of a hidden node and of a
     * removed one alike, so only `isConnected` tells them apart. It is read in an isolated world,
     * out of the page's sight. A refusal here means Chrome can no longer name the node, or the
     * document it was in has gone.
     */
    private suspend fun inDocument(cdp: CdpSession, params: JsonObject): Boolean =
        try {
            val world = JsonPrimitive(isolatedWorld(cdp))
            val node = resolvedNode(cdp, JsonObject(params + ("executionContextId" to world)))
            node != null &&
                callOn(cdp, node, "return this.isConnected;", awaitPromise = false).value ==
                    JsonPrimitive(true)
        } catch (e: ApiException) {
            if (e.rejected == null) throw e
            false
        }

    /**
     * The DOM params of [ref] from [targetId]'s last snapshot, once the page still has the document
     * that snapshot read.
     */
    private suspend fun refParams(cdp: CdpSession, targetId: String, ref: String): JsonObject {
        val snapshot =
            axTrees.get(targetId) ?: throw staleRef(ref, "no snapshot of this tab is kept", null)
        val loader = mainFrame(cdp).loaderId
        if (loader == null || loader != snapshot.loaderId) {
            throw staleRef(ref, "the tab has loaded another document since the snapshot", null)
        }
        return backendParams(elementBackendId(snapshot.tree, ref))
    }

    /**
     * The quads of the node [params] names, once it is scrolled into view. A node with no box is
     * `offscreen`, which Chrome says with an empty list or by refusing the call. It refuses with
     * [SERVER_ERROR] for a node with no layout, one that has left the page, and an id it has
     * dropped, and only the text differs. None of those has a box now, so nothing here reads the
     * text. The refusal stays the error's cause, for a caller that needs to know more.
     */
    private suspend fun boxed(cdp: CdpSession, params: JsonObject, scroll: Boolean): JsonArray {
        val quads =
            try {
                if (scroll) cdp.call("DOM.scrollIntoViewIfNeeded", params)
                quadsOf(cdp.call("DOM.getContentQuads", params))
            } catch (e: ApiException) {
                if (e.rejected?.code != SERVER_ERROR) throw e
                throw noBox(e)
            }
        if (quads.isEmpty()) throw noBox()
        return quads
    }

    private fun backendParams(backend: Int): JsonObject = buildJsonObject {
        put("backendNodeId", backend)
    }

    private fun quadsOf(result: JsonObject): JsonArray {
        val quads = result.decodeCdp<QuadResult>()?.quads ?: return JsonArray(emptyList())
        return JsonArray(quads)
    }

    private suspend fun cssNode(cdp: CdpSession, selector: String): JsonObject {
        val nodeId = firstMatch(cdp, selector)
        if (nodeId == 0) throw ApiException.notFound("No element matches the selector")
        return buildJsonObject { put("nodeId", nodeId) }
    }

    /** Gives [target] the page's focus. */
    suspend fun focus(cdp: CdpSession, id: String, target: TapTarget) {
        when (target) {
            is TapTarget.Ref -> cdp.call("DOM.focus", refParams(cdp, id, target.id))
            is TapTarget.Css ->
                cdp.withNodeIds { cdp.call("DOM.focus", cssNode(cdp, target.selector)) }
        }
    }

    private companion object {
        const val ELEMENT_POLL_MS = 200L
    }
}

/** A node with no box, keeping Chrome's refusal from [refused] as the cause when it gave one. */
private fun noBox(refused: ApiException? = null): ApiException =
    ApiException(409, "offscreen", "The node has no box on the page", cause = refused?.cause)

private fun staleRef(ref: String, why: String, cause: Throwable?): ApiException =
    ApiException(
        404,
        "stale_ref",
        "Ref $ref is from an older snapshot: $why. Take a new browser_snapshot.",
        cause = cause,
    )

@Serializable private data class QuadResult(val quads: List<JsonElement> = emptyList())
