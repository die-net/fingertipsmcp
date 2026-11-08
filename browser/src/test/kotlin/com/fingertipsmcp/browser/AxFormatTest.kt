package com.fingertipsmcp.browser

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserSnapshotOptions
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AxFormatTest {
    @Test
    fun `skips ignored nodes`() {
        val tree =
            ApiJson.parseToJsonElement(
                    """{"nodes":[{"nodeId":"1","role":{"value":"RootWebArea"},"name":{"value":"Hello"},"childIds":["2","3"]},{"nodeId":"2","ignored":true,"role":{"value":"generic"},"childIds":["4"]},{"nodeId":"3","role":{"value":"button"},"name":{"value":"Go"},"properties":[{"name":"focused","value":{"type":"boolean","value":true}}]},{"nodeId":"4","role":{"value":"StaticText"},"name":{"value":"Welcome"}}]}"""
                )
                .jsonObject
        val text = formatAxTree(tree, "https://example.com/")
        assertEquals("Hello", text.title)
        assertEquals(
            listOf(
                    """# title="Hello" url=https://example.com/""",
                    """[1] RootWebArea "Hello"""",
                    """  [4] StaticText "Welcome"""",
                    """  [3] button "Go" focused""",
                )
                .joinToString("\n"),
            text.compact,
        )
    }

    @Test
    fun `joins letter nodes`() {
        val tree =
            ApiJson.parseToJsonElement(
                    """{"nodes":[{"nodeId":"1","role":{"value":"paragraph"},"childIds":["2","3","4","5"]},{"nodeId":"2","role":{"value":"StaticText"},"name":{"value":"G"}},{"nodeId":"3","role":{"value":"StaticText"},"name":{"value":"o"}},{"nodeId":"4","role":{"value":"StaticText"},"name":{"value":" "}},{"nodeId":"5","role":{"value":"StaticText"},"name":{"value":"!"}}]}"""
                )
                .jsonObject
        val text = formatAxTree(tree)
        assertEquals(
            listOf(
                    "#",
                    """[1] paragraph""",
                    """  [2] StaticText "Go !"""",
                )
                .joinToString("\n"),
            text.compact,
        )
    }

    @Test
    fun `counts the nodes it leaves out`() {
        val tree =
            ApiJson.parseToJsonElement(
                    """{"nodes":[{"nodeId":"1","role":{"value":"list"},"childIds":["2","3","4"]},{"nodeId":"2","role":{"value":"link"},"name":{"value":"One"}},{"nodeId":"3","role":{"value":"link"},"name":{"value":"Two"}},{"nodeId":"4","role":{"value":"link"},"name":{"value":"Three"}}]}"""
                )
                .jsonObject
        val text = formatAxTree(tree, options = BrowserSnapshotOptions(maxNodes = 2))
        assertEquals(
            listOf(
                    "# truncated",
                    "# showing 2 of 4 nodes; raise maxNodes or maxChars for more",
                    """[1] list""",
                    """  [2] link "One"""",
                )
                .joinToString("\n"),
            text.compact,
        )
        val byChars = formatAxTree(tree, options = BrowserSnapshotOptions(maxChars = 30))
        assertEquals(
            "# showing 2 of 4 nodes; raise maxNodes or maxChars for more",
            byChars.compact.lines()[1],
        )
    }

    @Test
    fun `round trips a target id`() {
        assertEquals(
            "chrome_devtools_remote" to "abc",
            parseBrowserTargetId(browserTargetId("chrome_devtools_remote", "abc")),
        )
        assertThrows(ApiException::class.java) { parseBrowserTargetId("nope") }
    }
}
