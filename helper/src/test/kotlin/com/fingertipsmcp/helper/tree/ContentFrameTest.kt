package com.fingertipsmcp.helper.tree

import android.graphics.Rect as AndroidRect
import android.view.accessibility.AccessibilityNodeInfo
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Chrome's window as UiAutomation showed it on Chrome 133, with the page's nodes on and off. */
class ContentFrameTest {
    private val page = AndroidRect(0, 283, 1080, 2339)

    private fun window(view: FakeNode) =
        FakeNode(
            children =
                listOf(
                    FakeNode(
                        id = "com.android.chrome:id/toolbar",
                        bounds = AndroidRect(0, 0, 1080, 283),
                    ),
                    FakeNode(
                        id = "com.android.chrome:id/compositor_view_holder",
                        bounds = page,
                        children = listOf(view),
                    ),
                )
        )

    private val nodesOn =
        window(
            FakeNode(
                kind = "android.webkit.WebView",
                description = "Web View",
                bounds = page,
                children = listOf(FakeNode(kind = "android.widget.EditText")),
            )
        )
    private val nodesOff = window(FakeNode(description = "Web View", bounds = page))

    private fun frame(roots: () -> List<AccessibilityNodeInfo>) =
        ContentFrame(NodeCacheAge(clearCache = { true }, now = { 0L })) { roots() }

    @Test
    fun `a page with nodes has bounds`() {
        val found = frame { listOf(nodesOn) }.find("com.android.chrome")
        assertEquals(Rect(0, 283, 1080, 2339), found)
        assertFalse(frame { listOf(nodesOn) }.nodesOff())
    }

    @Test
    fun `an empty page is not off`() {
        val blank =
            window(
                FakeNode(kind = "android.webkit.WebView", description = "Web View", bounds = page)
            )
        assertFalse(frame { listOf(blank) }.nodesOff())
    }

    @Test
    fun `a bare view is nodes off`() {
        val failure =
            assertThrows(ApiException::class.java) {
                frame { listOf(nodesOff) }.find("com.android.chrome")
            }
        assertEquals("page_nodes_off", failure.error)
        assertTrue(frame { listOf(nodesOff) }.nodesOff())
    }

    @Test
    fun `a dialog over it is no page`() {
        val covered = FakeNode(children = listOf(FakeNode(kind = "android.widget.Button")))
        val failure =
            assertThrows(ApiException::class.java) {
                frame { listOf(covered) }.find("com.android.chrome")
            }
        assertEquals("content_unavailable", failure.error)
        assertFalse(frame { listOf(covered) }.nodesOff())
    }

    @Test
    fun `an old read is not trusted`() {
        // Chrome sends no event for a tab brought forward, so only the cache's age clears it.
        var clock = 0L
        var cached: FakeNode? = null
        var showing = FakeNode(children = emptyList())
        val cache =
            NodeCacheAge(
                clearCache = {
                    cached = null
                    true
                },
                now = { clock },
            )
        val frame = ContentFrame(cache) { _ -> listOf(cached ?: showing.also { cached = it }) }
        assertThrows(ApiException::class.java) { frame.find("com.android.chrome") }
        showing = nodesOn
        clock += MAX_NODE_CACHE_AGE_MS
        assertEquals(Rect(0, 283, 1080, 2339), frame.find("com.android.chrome"))
    }
}
