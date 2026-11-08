package com.fingertipsmcp.helper.tree

import com.fingertipsmcp.model.DeviceStateSummary
import com.fingertipsmcp.model.ImeState
import com.fingertipsmcp.model.KeyguardState
import com.fingertipsmcp.model.NodeSelector
import com.fingertipsmcp.model.Rect
import com.fingertipsmcp.model.ScreenState
import com.fingertipsmcp.model.UiNode
import com.fingertipsmcp.model.UiWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompactFormatterTest {
    private val bounds = Rect(0, 600, 1080, 660)

    private fun node(
        ref: String,
        role: String = "view",
        text: String? = null,
        desc: String? = null,
        id: String? = null,
        states: List<String> = emptyList(),
        actions: List<String> = emptyList(),
        children: List<UiNode> = emptyList(),
    ) = UiNode(ref, role, null, text, desc, null, id, bounds, states, actions, children)

    private val state =
        DeviceStateSummary(
            ScreenState.ON,
            KeyguardState(locked = false, secure = false),
            ImeState(visible = true, bounds = Rect(0, 1500, 1080, 2400), packageName = "kbd"),
            "com.android.settings",
        )

    @Test
    fun `header lists device state`() {
        assertEquals(
            "# seq=7 screen=on keyguard=unlocked ime=shown[0,1500,1080,2400] kbd fg=com.android.settings",
            CompactFormatter.header(7, state),
        )
    }

    @Test
    fun `row absorbs labels`() {
        val row =
            node(
                "e12",
                role = "button",
                states = listOf("clickable", "focusable"),
                actions = listOf("click", "focus"),
                children =
                    listOf(
                        node("e13", children = listOf(node("e14", role = "image"))),
                        node(
                            "e15",
                            id = "text_frame",
                            children =
                                listOf(
                                    node("e16", role = "text", text = "Network & internet"),
                                    node("e17", role = "text", text = "Mobile, Wi‑Fi"),
                                ),
                        ),
                    ),
            )
        val window =
            UiWindow(
                id = 3,
                type = "application",
                title = "Settings",
                packageName = "com.android.settings",
                layer = 1,
                focused = true,
                active = true,
                bounds = Rect(0, 0, 1080, 2400),
                root = node("e1", children = listOf(row)),
            )
        val out = CompactFormatter.format("# h", listOf(window))
        val expected =
            listOf(
                "# h",
                """[w3 app com.android.settings "Settings" focused]""",
                """  [e12] button "Network & internet · Mobile, Wi‑Fi" (540,630) {clickable}""",
            )
        assertEquals(expected.joinToString("\n", postfix = "\n"), out)
    }

    @Test
    fun `flags and extra actions`() {
        val sw =
            node(
                "e5",
                role = "switch",
                text = "Wi-Fi",
                states = listOf("clickable", "checkable", "obscured"),
                actions = listOf("click", "expand", "Delete"),
            )
        val line = CompactFormatter.format("#", listOf(window(sw))).lines()[2]
        assertEquals(
            "  [e5] switch \"Wi-Fi\" (540,630) {clickable,unchecked,obscured} actions:expand,Delete",
            line,
        )
    }

    @Test
    fun `content description is labeled`() {
        val button = node("e8", role = "button", desc = "Apps list", states = listOf("clickable"))
        val line = CompactFormatter.format("#", listOf(window(button))).lines()[2]
        assertEquals("""  [e8] button desc:"Apps list" (540,630) {clickable}""", line)
    }

    @Test
    fun `reports what it leaves out`() {
        val texts = (1..3).map { node("e$it", role = "text", text = "Line $it") }
        val win = window(node("e0", children = texts))
        val out =
            CompactFormatter.format(
                "# h",
                listOf(win),
                OutlineLimits(maxNodes = 1),
                TreeCut(maxDepth = 4),
            )
        assertEquals(
            listOf(
                "# h truncated",
                "# showing 1 of 3 nodes; raise maxNodes or maxChars for more",
                "# nodes below maxDepth 4 left out",
                CompactFormatter.windowLine(win),
                """  [e1] text "Line 1" (540,630)""",
                "",
            ),
            out.lines(),
        )
        assertEquals(
            listOf("# h", CompactFormatter.windowLine(win)),
            CompactFormatter.format("# h", listOf(win)).lines().take(2),
        )
    }

    @Test
    fun `text only drops the rest`() {
        val sw =
            node(
                "e5",
                role = "switch",
                text = "Wi-Fi",
                id = "toggle",
                states = listOf("clickable", "checkable"),
                actions = listOf("click", "expand"),
            )
        val out = CompactFormatter.format("#", listOf(window(sw)), OutlineLimits(textOnly = true))
        assertEquals("""  [e5] switch "Wi-Fi"""", out.lines()[2])
    }

    @Test
    fun `quote escapes and clips`() {
        assertEquals(""""say \"hi\""""", CompactFormatter.quote("""say "hi""""))
        assertEquals(""""a\nb"""", CompactFormatter.quote("a\nb"))
        assertTrue(CompactFormatter.quote("x".repeat(300)).endsWith("…\""))
    }

    @Test
    fun `selector matching`() {
        val n = node("e1", role = "text", text = "Network & internet", id = "title")
        assertTrue(SelectorMatcher(NodeSelector(text = "network & INTERNET")).matches(n, "p"))
        assertTrue(SelectorMatcher(NodeSelector(textContains = "work")).matches(n, "p"))
        assertTrue(SelectorMatcher(NodeSelector(textRegex = "^Net")).matches(n, "p"))
        assertTrue(SelectorMatcher(NodeSelector(id = "android:id/title")).matches(n, "p"))
        assertFalse(SelectorMatcher(NodeSelector(text = "Network")).matches(n, "p"))
        assertFalse(SelectorMatcher(NodeSelector(clickable = true)).matches(n, "p"))
        assertFalse(SelectorMatcher(NodeSelector(packageName = "q")).matches(n, "p"))
        assertTrue(SelectorMatcher(NodeSelector(ref = "e1")).isEmpty)
    }

    @Test
    fun `roles from classes and hints`() {
        assertEquals("button", Roles.roleOf(RoleHints("android.widget.ImageButton")))
        assertEquals("edit", Roles.roleOf(RoleHints("com.x.SearchEditText")))
        assertEquals(
            "switch",
            Roles.roleOf(RoleHints("com.google.android.material.materialswitch.MaterialSwitch")),
        )
        assertEquals("list", Roles.roleOf(RoleHints("androidx.recyclerview.widget.RecyclerView")))
        assertEquals("scroll", Roles.roleOf(RoleHints("androidx.core.widget.NestedScrollView")))
        assertEquals(
            "button",
            Roles.roleOf(RoleHints("android.widget.LinearLayout", clickable = true)),
        )
        assertEquals("view", Roles.roleOf(RoleHints("android.widget.FrameLayout")))
        assertEquals(
            "check",
            Roles.roleOf(RoleHints("android.view.View", roleDescription = "Checkbox")),
        )
        assertEquals("edit", Roles.roleOf(RoleHints("android.view.View", editable = true)))
    }

    private fun window(root: UiNode) =
        UiWindow(1, "application", null, "p", 1, false, false, Rect(0, 0, 1, 1), root)
}
