package net.die.phoneapi.browser

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.model.ApiException

/**
 * Runs [steps], which take node ids from Chrome and use them over several calls, and returns what
 * they found on a document that held still.
 *
 * Chrome drops every node id and search a session holds when the page commits a document, and again
 * at DOMContentLoaded, so these ids can go between any two calls while a page loads. It tells the
 * session with `DOM.documentUpdated`, ahead of its reply to any command it could no longer answer.
 * So [steps] start over when that event came while they ran: what they found may be from a document
 * that has gone, and a refusal then says only that the ids went. A refusal with no such event is
 * Chrome's answer, and is final. `DOM.querySelector` takes only a node id, so a lookup can't avoid
 * holding one, as a caller that already has a node can with its `backendNodeId`.
 *
 * A load drops the ids twice, so one that crosses both gets [NODE_ID_TRIES] tries. The last try's
 * outcome stands whatever it crossed.
 */
internal suspend fun <T> CdpSession.withNodeIds(steps: suspend () -> T): T {
    repeat(NODE_ID_TRIES - 1) {
        val dropped = AtomicBoolean(false)
        listen { event -> if (event.method == DOCUMENT_UPDATED) dropped.set(true) }
            .use {
                try {
                    val found = steps()
                    if (!dropped.get()) return found
                } catch (e: ApiException) {
                    if (e.rejected == null || !dropped.get()) throw e
                }
            }
    }
    return steps()
}

/** The node id of the page's document, for use within [withNodeIds]. */
internal suspend fun documentNode(cdp: CdpSession): Int {
    val id =
        cdp.call("DOM.getDocument", buildJsonObject { put("depth", 0) })
            .decodeCdp<DocumentResult>()
            ?.root
            ?.nodeId ?: 0
    if (id == 0) throw ApiException(502, "cdp_error", "Chrome did not return a document")
    return id
}

/** The node id of the first element [selector] matches, or 0, for use within [withNodeIds]. */
internal suspend fun firstMatch(cdp: CdpSession, selector: String): Int {
    val root = documentNode(cdp)
    return cdp.call(
            "DOM.querySelector",
            buildJsonObject {
                put("nodeId", root)
                put("selector", selector)
            },
        )
        .decodeCdp<NodeRef>()
        ?.nodeId ?: 0
}

/** Chrome's event that the page has a new document, or its document finished parsing. */
private const val DOCUMENT_UPDATED = "DOM.documentUpdated"

private const val NODE_ID_TRIES = 3

@Serializable private data class DocumentResult(val root: NodeRef? = null)

@Serializable private data class NodeRef(val nodeId: Int = 0)
