package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpBrowserConnection
import com.fingertipsmcp.browser.CdpTransport
import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserTarget
import com.fingertipsmcp.stream.VideoSpec
import java.io.File
import java.lang.reflect.Proxy
import java.util.Collections
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ChromeViewerTest {
    @TempDir lateinit var home: File

    @Test fun `failed video start stops`() = assertStops { viewer -> viewer.watchVideo(SPEC) {} }

    @Test fun `failed audio start stops`() = assertStops { viewer -> viewer.watchAudio {} }

    /**
     * A viewer whose capture fails to start because Chrome is not up yet, then one that starts it
     * and leaves. Capture stops as the second leaves, since the first counts as gone.
     */
    private fun assertStops(watch: suspend (ChromeViewer) -> Unit) = runTest {
        FakeChrome().use { chrome ->
            CdpBrowserConnection(chrome, StandardTestDispatcher(testScheduler)).use { connection ->
                connection.start()
                var starts = 0
                val viewer =
                    ChromeViewer(
                        connection = {
                            starts += 1
                            if (starts == 1) {
                                throw ApiException.unavailable("chrome_stopped", "Not yet")
                            }
                            connection
                        },
                        browser = showingOnePage(),
                        extensionHome = home,
                        scope = backgroundScope,
                    )
                assertThrows<ApiException> { watch(viewer) }
                watch(viewer)
                assertTrue(chrome.evaluated.any { it.startsWith("fingertipsViewer.capture(") })
                assertTrue(
                    "fingertipsViewer.stop()" in chrome.evaluated,
                    chrome.evaluated.toString(),
                )
            }
        }
    }

    /** A browser that lists one page, shown. */
    private fun showingOnePage(): BrowserService =
        Proxy.newProxyInstance(
            BrowserService::class.java.classLoader,
            arrayOf(BrowserService::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "targets" ->
                    listOf(BrowserTarget(id = "chrome~PAGE", type = "page", socket = "chrome"))
                "showing" -> true
                else -> error("unexpected ${method.name}")
            }
        } as BrowserService

    /**
     * DevTools for a Chrome with one tab, PAGE in tab target TAB, and the viewer's offscreen
     * document DOC. Each command is answered once it is sent, after any event it causes.
     */
    private class FakeChrome : CdpTransport {
        private val out = Channel<String>(Channel.UNLIMITED)
        val evaluated: MutableList<String> = Collections.synchronizedList(ArrayList())

        override fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message.getValue("id").jsonPrimitive.content.toInt()
            val method = message.getValue("method").jsonPrimitive.content
            val params = message["params"]?.jsonObject ?: JsonObject(emptyMap())
            val session = message["sessionId"]?.jsonPrimitive?.content
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

        private fun answer(method: String, params: JsonObject, session: String?): JsonElement =
            when (method) {
                "Extensions.loadUnpacked" -> buildJsonObject { put("id", "ext") }
                "Target.getTargets" -> targets(params.toString().contains("\"tab\""))
                "Target.attachToTarget" ->
                    buildJsonObject {
                        put("sessionId", "s-" + params.getValue("targetId").jsonPrimitive.content)
                    }
                "Target.setAutoAttach" -> {
                    out.trySend(
                        buildJsonObject {
                            put("method", "Target.attachedToTarget")
                            session?.let { put("sessionId", it) }
                            putJsonObject("params") {
                                put("sessionId", "child")
                                putJsonObject("targetInfo") { put("targetId", "PAGE") }
                            }
                        }
                            .toString()
                    )
                    JsonObject(emptyMap())
                }
                "Page.getLayoutMetrics" ->
                    buildJsonObject {
                        putJsonObject("cssVisualViewport") {
                            put("clientWidth", 600)
                            put("clientHeight", 1000)
                        }
                        putJsonObject("visualViewport") {
                            put("clientWidth", 1200)
                            put("clientHeight", 2000)
                        }
                    }
                "Runtime.evaluate" -> {
                    evaluated += params.getValue("expression").jsonPrimitive.content
                    buildJsonObject {
                        putJsonObject("result") { putJsonObject("value") { put("ok", true) } }
                    }
                }
                else -> JsonObject(emptyMap())
            }

        private fun targets(tabs: Boolean) = buildJsonObject {
            put(
                "targetInfos",
                buildJsonArray {
                    if (tabs) {
                        add(buildJsonObject { put("targetId", "TAB") })
                    } else {
                        add(
                            buildJsonObject {
                                put("targetId", "DOC")
                                put("type", "background_page")
                                put("url", "chrome-extension://ext/offscreen.html")
                            }
                        )
                    }
                },
            )
        }

        override suspend fun receive(onText: suspend (String) -> Unit) {
            for (text in out) onText(text)
        }

        override fun close() {
            out.close()
        }
    }

    private companion object {
        val SPEC = VideoSpec(1280, 30, 4_000_000)
    }
}
