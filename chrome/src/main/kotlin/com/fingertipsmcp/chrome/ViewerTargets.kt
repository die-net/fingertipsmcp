package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpBrowserConnection
import com.fingertipsmcp.browser.CdpEvent
import com.fingertipsmcp.browser.OpenCdpSession
import com.fingertipsmcp.browser.decodeCdp
import com.fingertipsmcp.model.ApiException
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * What the viewer learns of one browser's targets from Chrome's own events, on a browser session of
 * its own: the tab target that holds each page, which `Extensions.triggerAction` takes, and the
 * extension documents that are open.
 *
 * DevTools lists a tab target apart from its page with nothing linking them. Chrome attaches a page
 * only through its tab, so each tab gets a session whose `Target.setAutoAttach` reports the one
 * page in it. A tab Chrome destroys is dropped. Its events arrive on the reader, so nothing here
 * waits there; a tab's session opens in [scope].
 */
internal class ViewerTargets
private constructor(
    private val connection: CdpBrowserConnection,
    private val session: OpenCdpSession,
    private val scope: CoroutineScope,
) : Closeable {
    /** Page target id to the id of the tab target that holds it. */
    private val tabOf = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Extension documents, by URL, to their target id. */
    private val documents = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Tab targets a session is open or opening for, so each is linked once. */
    private val linked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The tab target that holds the page [chromeId], waiting for Chrome to report it. */
    suspend fun tabOf(chromeId: String): String =
        withTimeoutOrNull(LINK_WAIT_MS) { tabOf.first { chromeId in it }.getValue(chromeId) }
            ?: throw ApiException.notFound("No tab holds the page $chromeId")

    /** The target id of the extension document at [url], waiting for it to open. */
    suspend fun document(url: String): String? =
        withTimeoutOrNull(DOCUMENT_WAIT_MS) { documents.first { url in it }.getValue(url) }

    override fun close() {
        session.close()
    }

    private fun onEvent(event: CdpEvent) {
        when (event.method) {
            "Target.targetCreated",
            "Target.targetInfoChanged" -> {
                val info = event.params.decodeCdp<Changed>()?.targetInfo ?: return
                when (info.type) {
                    TAB -> if (linked.add(info.targetId)) scope.launch { link(info.targetId) }
                    DOCUMENT ->
                        info.url?.let { url -> documents.update { it + (url to info.targetId) } }
                }
            }
            "Target.targetDestroyed" -> {
                val gone = event.params.decodeCdp<Destroyed>()?.targetId ?: return
                linked -= gone
                tabOf.update { map -> map.filter { (page, tab) -> page != gone && tab != gone } }
                documents.update { map -> map.filterValues { it != gone } }
            }
        }
    }

    /**
     * Opens a session on the tab [tabId] that reports the page in it. The page's own session is
     * detached once it names the page, since only its id is wanted. The tab's session stays, so a
     * page that replaces the one in the tab is reported too, and Chrome ends it with the tab.
     */
    @Suppress("MissingUseCall") // Chrome detaches it when the tab closes, or the connection does.
    private suspend fun link(tabId: String) {
        val tab =
            try {
                connection.attach(tabId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // The tab closed before it could be attached, which its destroyed event follows.
                log.debug("Could not attach to tab {}: {}", tabId, e.message)
                linked -= tabId
                return
            }
        tab.listen { event ->
            if (event.method != "Target.attachedToTarget") return@listen
            val attached = event.params.decodeCdp<Attached>() ?: return@listen
            val page = attached.targetInfo?.takeIf { it.type == PAGE }?.targetId
            if (page != null && tabId in linked) tabOf.update { it + (page to tabId) }
            attached.sessionId?.let { child ->
                connection.root.post(
                    "Target.detachFromTarget",
                    buildJsonObject { put("sessionId", child) },
                )
            }
        }
        try {
            tab.call(
                "Target.setAutoAttach",
                buildJsonObject {
                    put("autoAttach", true)
                    put("waitForDebuggerOnStart", false)
                    put("flatten", true)
                    put("filter", filter(PAGE))
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            log.debug("Could not watch tab {}: {}", tabId, e.message)
            tab.close()
            linked -= tabId
            return
        }
        // Destroyed while it was being linked.
        if (tabId !in linked) tab.close()
    }

    @Serializable
    private data class TargetInfo(
        val targetId: String,
        val type: String? = null,
        val url: String? = null,
    )

    @Serializable private data class Changed(val targetInfo: TargetInfo? = null)

    @Serializable private data class Destroyed(val targetId: String? = null)

    @Serializable
    private data class Attached(val sessionId: String? = null, val targetInfo: TargetInfo? = null)

    companion object {
        /**
         * Starts watching the targets of [connection]. Chrome reports the targets already open as
         * created, so nothing is missed between listening and asking.
         */
        @Suppress("MissingUseCall") // The returned watch owns the session.
        suspend fun open(connection: CdpBrowserConnection, scope: CoroutineScope): ViewerTargets {
            val session = connection.attachBrowser()
            val targets = ViewerTargets(connection, session, scope)
            var watching = false
            try {
                session.listen(targets::onEvent)
                // Every target: Chrome creates an extension's document as type "other", with no
                // URL, and names it only in a later change, which a filter on its type hides.
                session.call(
                    "Target.setDiscoverTargets",
                    buildJsonObject {
                        put("discover", true)
                        put("filter", buildJsonArray { add(buildJsonObject {}) })
                    },
                )
                watching = true
            } finally {
                if (!watching) session.close()
            }
            return targets
        }

        private const val TAB = "tab"
        private const val PAGE = "page"

        /**
         * What DevTools lists an extension's offscreen document as. A tab of the same URL comes
         * too.
         */
        private const val DOCUMENT = "background_page"
        private const val LINK_WAIT_MS = 2_000L
        private const val DOCUMENT_WAIT_MS = 5_000L

        private fun filter(vararg types: String) = buildJsonArray {
            types.forEach { type -> add(buildJsonObject { put("type", type) }) }
        }

        private val log = LoggerFactory.getLogger("FingertipsChrome.ViewerTargets")
    }
}
