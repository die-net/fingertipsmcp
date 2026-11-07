package net.die.phoneapi.browser

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.model.ApiException

/**
 * Finds the element a tap or focus names on a page: a snapshot ref, through the target's last
 * accessibility tree in [axTrees], or a CSS selector. A ref holds only on the document its snapshot
 * read, so one from another document, or whose node has gone, is `stale_ref`, never looked up again
 * by an id that may name another element now.
 */
internal class ElementLocator(private val axTrees: AxTreeCache) {
    /** The content quads of [target], scrolled into view first. */
    suspend fun quads(
        cdp: CdpSession,
        targetId: String,
        target: TapTarget,
    ): JsonArray =
        when (target) {
            is TapTarget.Ref -> refQuads(cdp, targetId, target.id)
            is TapTarget.Css -> cssQuads(cdp, target)
        }

    /**
     * Polls until [target] matches an element with a box, or its wait is up. Then the last reason
     * it did not, no match or no box, is the failure.
     */
    private suspend fun cssQuads(cdp: CdpSession, target: TapTarget.Css): JsonArray {
        var why: ApiException? = null
        suspend fun attempt(): JsonArray? =
            try {
                boxed(cdp, cssNode(cdp, target.selector))
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

    private suspend fun refQuads(cdp: CdpSession, targetId: String, ref: String): JsonArray {
        val params = refParams(cdp, targetId, ref)
        try {
            return boxed(cdp, params)
        } catch (e: ApiException) {
            // Chrome refuses a node id the document no longer has.
            if (e.rejected == null) throw e
            throw staleRef(ref, "its node has left the page", e)
        }
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

    private suspend fun boxed(cdp: CdpSession, params: JsonObject): JsonArray {
        cdp.call("DOM.scrollIntoViewIfNeeded", params)
        val quads = quadsOf(cdp.call("DOM.getContentQuads", params))
        if (quads.isEmpty()) throw ApiException(409, "offscreen", "The node has no box on the page")
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
        val root =
            cdp.call("DOM.getDocument", buildJsonObject { put("depth", 0) })
                .decodeCdp<DocumentResult>()
                ?.root
                ?.nodeId ?: 0
        if (root == 0) throw ApiException(502, "cdp_error", "Chrome did not return a document")
        val nodeId =
            cdp.call(
                    "DOM.querySelector",
                    buildJsonObject {
                        put("nodeId", root)
                        put("selector", selector)
                    },
                )
                .decodeCdp<QueryResult>()
                ?.nodeId ?: 0
        if (nodeId == 0) throw ApiException.notFound("No element matches the selector")
        return buildJsonObject { put("nodeId", nodeId) }
    }

    /** The DOM params that name [target], for focusing it. */
    suspend fun focus(cdp: CdpSession, id: String, target: TapTarget): JsonObject =
        when (target) {
            is TapTarget.Ref -> refParams(cdp, id, target.id)
            is TapTarget.Css -> cssNode(cdp, target.selector)
        }

    private companion object {
        const val ELEMENT_POLL_MS = 200L
    }
}

private fun staleRef(ref: String, why: String, cause: Throwable?): ApiException =
    ApiException(
        404,
        "stale_ref",
        "Ref $ref is from an older snapshot: $why. Take a new browser_snapshot.",
        cause = cause,
    )

@Serializable private data class DocumentResult(val root: NodeId? = null)

@Serializable private data class NodeId(val nodeId: Int = 0)

@Serializable private data class QueryResult(val nodeId: Int = 0)

@Serializable private data class QuadResult(val quads: List<JsonElement> = emptyList())
