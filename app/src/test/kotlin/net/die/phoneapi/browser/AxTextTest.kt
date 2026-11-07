package net.die.phoneapi.browser

import kotlinx.serialization.json.jsonObject
import net.die.phoneapi.core.ApiJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AxTextTest {
    @Test
    fun `keeps a node it cannot read in its place`() {
        // Node 2's role is not the object Chrome sends, so it does not decode. Its child stays
        // under the root, where it was, rather than becoming a root of its own after it.
        val tree =
            ApiJson.parseToJsonElement(
                    """{"nodes":[{"nodeId":"1","role":{"value":"RootWebArea"},"name":{"value":"Hi"},""" +
                        """"childIds":["2","3"]},{"nodeId":"2","role":"group","childIds":["4"]},""" +
                        """{"nodeId":"3","role":{"value":"button"},"name":{"value":"Go"}},""" +
                        """{"nodeId":"4","role":{"value":"link"},"name":{"value":"Home"}}]}"""
                )
                .jsonObject
        assertEquals(
            listOf(
                    """# title="Hi" truncated""",
                    "# nodes that could not be read: 1; their children stand in their place",
                    """[1] RootWebArea "Hi"""",
                    """  [4] link "Home"""",
                    """  [3] button "Go"""",
                )
                .joinToString("\n"),
            formatAxTree(tree).compact,
        )
    }
}
