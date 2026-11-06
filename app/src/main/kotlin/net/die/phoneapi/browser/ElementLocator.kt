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
 * accessibility tree in [axTrees], or a CSS selector. A ref whose node has gone fetches the tree
 * again.
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
        val backend = cachedBackend(targetId, ref)
        if (backend != null) {
            try {
                return boxed(cdp, backendParams(backend))
            } catch (e: ApiException) {
                if (e.error != "cdp_error") throw e
                axTrees.remove(targetId)
            }
        }
        return boxed(cdp, backendParams(fetchBackend(cdp, targetId, ref)))
    }

    private suspend fun boxed(cdp: CdpSession, params: JsonObject): JsonArray {
        cdp.call("DOM.scrollIntoViewIfNeeded", params)
        val quads = quadsOf(cdp.call("DOM.getContentQuads", params))
        if (quads.isEmpty()) throw ApiException(409, "offscreen", "The node has no box on the page")
        return quads
    }

    private fun cachedBackend(targetId: String, ref: String): Int? {
        val tree = axTrees.get(targetId) ?: return null
        return try {
            elementBackendId(tree, ref)
        } catch (_: ApiException) {
            null
        }
    }

    private suspend fun fetchBackend(cdp: CdpSession, targetId: String, ref: String): Int {
        cdp.call("Accessibility.enable")
        val tree = cdp.call("Accessibility.getFullAXTree")
        axTrees.put(targetId, tree)
        return elementBackendId(tree, ref)
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
            is TapTarget.Ref ->
                backendParams(cachedBackend(id, target.id) ?: fetchBackend(cdp, id, target.id))
            is TapTarget.Css -> cssNode(cdp, target.selector)
        }

    private companion object {
        const val ELEMENT_POLL_MS = 200L
    }
}

@Serializable private data class DocumentResult(val root: NodeId? = null)

@Serializable private data class NodeId(val nodeId: Int = 0)

@Serializable private data class QueryResult(val nodeId: Int = 0)

@Serializable private data class QuadResult(val quads: List<JsonElement> = emptyList())
