package com.fingertipsmcp.browser

import com.fingertipsmcp.model.BrowserInput
import com.fingertipsmcp.model.BrowserKeyRequest
import com.fingertipsmcp.model.BrowserSnapshotOptions
import java.util.Collections
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A desktop browser selects tabs through the selector it was given, which leaves the app in front
 * as it was, and over DevTools, which brings Chrome in front of the person's other apps, only when
 * the selector can't.
 */
class DesktopSelectTest {
    @Test
    fun `show selects quietly`() = runTest {
        withService(this, selects = true) { chrome, service, asked ->
            service.snapshot(SECOND, BrowserSnapshotOptions(show = true))
            assertEquals("T2", chrome.front)
            assertTrue(asked.isNotEmpty() && asked.all { it == SECOND }, asked.toString())
            assertEquals(emptyList<String>(), chrome.focusTaking())
        }
    }

    @Test
    fun `show falls back`() = runTest {
        withService(this, selects = false) { chrome, service, asked ->
            service.snapshot(SECOND, BrowserSnapshotOptions(show = true))
            assertEquals("T2", chrome.front)
            assertTrue(asked.isNotEmpty())
            assertTrue("Page.bringToFront" in chrome.focusTaking(), chrome.methods().toString())
        }
    }

    @Test
    fun `input selects quietly`() = runTest {
        withService(this, selects = true) { chrome, service, asked ->
            service.key(SECOND, BrowserKeyRequest(key = "a", input = BrowserInput.CDP))
            assertEquals("T2", chrome.front)
            assertEquals(listOf(SECOND), asked)
            assertEquals(emptyList<String>(), chrome.focusTaking())
            assertTrue("Input.dispatchKeyEvent" in chrome.methods())
        }
    }

    @Test
    fun `input falls back`() = runTest {
        withService(this, selects = false) { chrome, service, asked ->
            service.key(SECOND, BrowserKeyRequest(key = "a", input = BrowserInput.CDP))
            assertEquals("T2", chrome.front)
            assertEquals(listOf(SECOND), asked)
            assertEquals(listOf("Page.bringToFront"), chrome.focusTaking())
        }
    }

    @Test
    fun `activate selects quietly`() = runTest {
        withService(this, selects = true) { chrome, service, asked ->
            service.activate(SECOND)
            assertEquals("T2", chrome.front)
            assertEquals(listOf(SECOND), asked)
            assertEquals(emptyList<String>(), chrome.focusTaking())
        }
    }

    @Test
    fun `activate falls back`() = runTest {
        withService(this, selects = false) { chrome, service, _ ->
            service.activate(SECOND)
            assertEquals("T2", chrome.front)
            assertEquals(listOf("Target.activateTarget"), chrome.focusTaking())
        }
    }

    @Test
    fun `shown open selects quietly`() = runTest {
        withService(this, selects = true) { chrome, service, asked ->
            val tab = service.openTab(BLANK)
            assertEquals("chrome~T3", tab.id)
            assertEquals(true, chrome.created.single().second)
            assertEquals("T3", chrome.front)
            assertEquals(listOf("chrome~T3"), asked)
            assertEquals(emptyList<String>(), chrome.focusTaking())
        }
    }

    @Test
    fun `shown open falls back`() = runTest {
        withService(this, selects = false) { chrome, service, _ ->
            service.openTab(BLANK)
            assertEquals(true, chrome.created.single().second)
            assertEquals("T3", chrome.front)
            assertEquals(listOf("Target.activateTarget"), chrome.focusTaking())
        }
    }

    @Test
    fun `background open selects none`() = runTest {
        withService(this, selects = true) { chrome, service, asked ->
            service.openInBackground(BLANK)
            assertEquals(true, chrome.created.single().second)
            assertEquals("T1", chrome.front)
            assertEquals(emptyList<String>(), asked)
            assertEquals(emptyList<String>(), chrome.focusTaking())
        }
    }

    /**
     * In a window another app covers, which its active tab reads as `hidden`, input raises the
     * window with `Page.bringToFront` before it selects anything, since Chrome drops input into a
     * tab it has not drawn, and selects nothing quietly.
     */
    @Test
    fun `covered input raises first`() = runTest {
        withService(this, selects = true, covered = true) { chrome, service, asked ->
            service.key(SECOND, BrowserKeyRequest(key = "a", input = BrowserInput.CDP))
            assertEquals(emptyList<String>(), asked)
            assertEquals(listOf("Page.bringToFront"), chrome.focusTaking())
            assertTrue("Input.dispatchKeyEvent" in chrome.methods())
        }
    }

    /** A show in a covered window raises it first, since a show is for the tab to be drawn. */
    @Test
    fun `covered show raises first`() = runTest {
        withService(this, selects = true, covered = true) { chrome, service, asked ->
            service.snapshot(SECOND, BrowserSnapshotOptions(show = true))
            assertEquals(emptyList<String>(), asked)
            assertEquals(listOf("Page.bringToFront"), chrome.focusTaking())
        }
    }

    /**
     * When the selector can't say which tab shows, input selects quietly, as with a drawn window.
     */
    @Test
    fun `unknown window selects quietly`() = runTest {
        withService(this, selects = true, knows = false) { chrome, service, asked ->
            service.key(SECOND, BrowserKeyRequest(key = "a", input = BrowserInput.CDP))
            assertEquals(listOf(SECOND), asked)
            assertEquals(emptyList<String>(), chrome.focusTaking())
        }
    }

    /** Activate needs the tab selected in its window, not drawn, so it raises nothing. */
    @Test
    fun `covered activate stays quiet`() = runTest {
        withService(this, selects = true, covered = true) { chrome, service, asked ->
            service.activate(SECOND)
            assertEquals(listOf(SECOND), asked)
            assertEquals("T2", chrome.front)
            assertEquals(emptyList<String>(), chrome.focusTaking())
        }
    }

    /** What the selector says Chrome shows wins over the page's visibility, which a cover hides. */
    @Test
    fun `showing asks the selector`() = runTest {
        withService(this, selects = true, covered = true) { _, service, _ ->
            service.activate(SECOND)
            assertEquals(true, service.showing(SECOND))
            assertEquals(false, service.showing(FIRST))
        }
    }

    /** A selector that can't say leaves the page's visibility to answer. */
    @Test
    fun `showing without the selector`() = runTest {
        withService(this, selects = true, knows = false) { _, service, _ ->
            assertEquals(true, service.showing(FIRST))
            assertEquals(false, service.showing(SECOND))
        }
    }

    /**
     * Runs [test] on a browser service for a [FakeDesktop] showing T1, with a selector that selects
     * a tab, as the extension does, when [selects], records the ids it was asked for, and, when it
     * [knows], says which tab Chrome shows. The window is [covered] by another app's.
     */
    private suspend fun withService(
        scope: TestScope,
        selects: Boolean,
        covered: Boolean = false,
        knows: Boolean = true,
        test: suspend (FakeDesktop, BrowserServiceImpl, List<String>) -> Unit,
    ) =
        FakeDesktop(covered).use { chrome ->
            val dispatcher = StandardTestDispatcher(scope.testScheduler)
            CdpBrowserConnection(chrome, dispatcher).use { connection ->
                connection.start()
                val asked = Collections.synchronizedList(ArrayList<String>())
                val service =
                    BrowserServiceImpl(
                        devtools = ConnectionEndpoints(connection = { connection }),
                        desktopInput = DesktopPointer(),
                        pendingScript = { "" },
                        selector =
                            object : TabSelector {
                                override suspend fun select(id: String): Boolean {
                                    asked += id
                                    if (selects) chrome.front = parseBrowserTargetId(id).second
                                    return selects
                                }

                                override suspend fun showing(): String? =
                                    if (knows) "chrome~${chrome.front}" else null
                            },
                    )
                test(chrome, service, asked)
            }
        }

    /**
     * A desktop Chrome with one window, whose tabs read `visible` only while the window shows them
     * and is not [covered] by another app's window. `Page.bringToFront`, `Target.activateTarget`,
     * and a tab opened without `background` select a tab, and bring Chrome in front of other apps
     * as they do on macOS, which uncovers the window. Each command is answered once it is sent.
     */
    private class FakeDesktop(@Volatile var covered: Boolean) : CdpTransport {
        private val out = Channel<String>(Channel.UNLIMITED)
        private val sent: MutableList<Pair<String, String?>> =
            Collections.synchronizedList(ArrayList())
        private val tabs = mutableListOf("T1", "T2")
        /** Tabs opened, with whether each asked for `background`. */
        val created: MutableList<Pair<String, Boolean>> = Collections.synchronizedList(ArrayList())
        @Volatile var front = "T1"

        fun methods(): List<String> = sent.map { it.first }

        /** The commands sent that bring Chrome in front, in order. */
        fun focusTaking(): List<String> =
            methods().filter { it in FOCUS_TAKING } +
                created.filter { !it.second }.map { "Target.createTarget" }

        override fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message.getValue("id").jsonPrimitive.int
            val method = message.getValue("method").jsonPrimitive.content
            val params = message["params"]?.jsonObject ?: JsonObject(emptyMap())
            val session = message["sessionId"]?.jsonPrimitive?.content
            sent += method to session
            val result = answer(method, params, session)
            out.trySend(
                buildJsonObject {
                    put("id", id)
                    session?.let { put("sessionId", it) }
                    put("result", result)
                }
                    .toString()
            )
        }

        /** Answers a command to the browser, or with [session], to a page. */
        private fun answer(method: String, params: JsonObject, session: String?): JsonElement {
            val page = session?.removePrefix("s-")
            return if (page == null || page == "browser") browser(method, params)
            else page(method, params, page)
        }

        private fun browser(method: String, params: JsonObject): JsonElement =
            when (method) {
                "Target.attachToBrowserTarget" -> obj { put("sessionId", "browser") }
                "Target.attachToTarget" ->
                    obj {
                        put("sessionId", "s-" + params.getValue("targetId").jsonPrimitive.content)
                    }
                "Target.getTargets" ->
                    obj {
                        putJsonArray("targetInfos") {
                            tabs.forEach { tab ->
                                add(
                                    obj {
                                        put("targetId", tab)
                                        put("type", "page")
                                        put("url", BLANK)
                                    }
                                )
                            }
                        }
                    }
                "Target.createTarget" -> {
                    val tab = "T${tabs.size + 1}"
                    tabs += tab
                    val background = params["background"]?.jsonPrimitive?.boolean == true
                    created += tab to background
                    if (!background) {
                        front = tab
                        cameForward()
                    }
                    obj { put("targetId", tab) }
                }
                "Target.activateTarget" -> {
                    front = params.getValue("targetId").jsonPrimitive.content
                    cameForward()
                    obj {}
                }
                else -> obj {}
            }

        private fun page(method: String, params: JsonObject, page: String): JsonElement =
            when (method) {
                "Page.bringToFront" -> {
                    front = page
                    cameForward()
                    obj {}
                }
                "Page.getFrameTree" ->
                    obj {
                        putJsonObject("frameTree") {
                            putJsonObject("frame") {
                                put("id", "F-$page")
                                put("url", BLANK)
                            }
                        }
                    }
                "Page.createIsolatedWorld" -> obj { put("executionContextId", 1) }
                "Page.getLayoutMetrics" ->
                    obj {
                        putJsonObject("cssVisualViewport") {
                            put("clientWidth", WIDTH)
                            put("clientHeight", HEIGHT)
                        }
                        putJsonObject("visualViewport") {
                            put("clientWidth", WIDTH)
                            put("clientHeight", HEIGHT)
                        }
                    }
                "Accessibility.getFullAXTree" -> obj { putJsonArray("nodes") {} }
                "Runtime.evaluate" -> evaluate(params, page)
                else -> obj {}
            }

        /** `document.visibilityState` reads as Chrome would; any other script gives 0. */
        private fun evaluate(params: JsonObject, page: String): JsonElement {
            val expression = params.getValue("expression").jsonPrimitive.content
            val value =
                if (expression == "document.visibilityState") {
                    JsonPrimitive(if (page == front && !covered) "visible" else "hidden")
                } else {
                    JsonPrimitive(0)
                }
            return obj {
                putJsonObject("result") {
                    put("type", if (value.isString) "string" else "number")
                    put("value", value)
                }
            }
        }

        /** Chrome came in front of other apps, which uncovers its window. */
        private fun cameForward() {
            covered = false
        }

        private fun obj(build: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(build)

        override suspend fun receive(onText: suspend (String) -> Unit) {
            for (text in out) onText(text)
        }

        override fun close() {
            out.close()
        }
    }

    private companion object {
        const val FIRST = "chrome~T1"
        const val SECOND = "chrome~T2"
        const val BLANK = "about:blank"
        const val WIDTH = 800
        const val HEIGHT = 600
        val FOCUS_TAKING = setOf("Page.bringToFront", "Target.activateTarget")
    }
}
