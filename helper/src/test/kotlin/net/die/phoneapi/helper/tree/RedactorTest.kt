package net.die.phoneapi.helper.tree

import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.UiNode
import net.die.phoneapi.model.UiWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RedactorTest {
    private fun node(
        ref: String,
        role: String = "text",
        text: String? = null,
        hint: String? = null,
        id: String? = null,
        states: List<String> = emptyList(),
    ) = UiNode(ref, role, null, text, null, hint, id, Rect(0, 0, 1, 1), states)

    private fun redact(vararg nodes: UiNode): Pair<List<UiNode>, Int> {
        val window =
            UiWindow(
                1,
                "application",
                null,
                "p",
                1,
                true,
                true,
                Rect(0, 0, 1, 1),
                node("e0").copy(children = nodes.toList()),
            )
        val redactor = Redactor()
        val out = redactor.windows(listOf(window)).single().root!!.children
        return out to redactor.count
    }

    @Test
    fun `masks secret fields`() {
        val (out, count) =
            redact(
                node("e1", "edit", "hunter2", states = listOf("editable", "password")),
                node("e2", "edit", "482913", id = "otp_input", states = listOf("editable")),
                node("e3", "edit", "", hint = "Verification code", states = listOf("editable")),
                node("e4", "edit", "94110", hint = "ZIP code", states = listOf("editable")),
            )
        assertEquals(listOf("[redacted]", "[redacted]", "", "94110"), out.map { it.text })
        assertEquals(2, count)
    }

    @Test
    fun `uses the label before a field`() {
        val (out, count) =
            redact(
                node("e1", text = "Code"),
                node("e2", "edit", "482913", states = listOf("editable")),
                node("e3", text = "ZIP code"),
                node("e4", "edit", "94110", states = listOf("editable")),
                node("e5", text = "Verification code"),
                node("e6", "edit", "AB12CD", states = listOf("editable")),
            )
        assertEquals(
            listOf("Code", "[redacted]", "ZIP code", "94110", "Verification code", "[redacted]"),
            out.map { it.text },
        )
        assertEquals(2, count)
    }

    @Test
    fun `masks card numbers`() {
        val (out, count) =
            redact(
                node("e1", text = "Card 4111 1111 1111 1111 on file"),
                node("e2", text = "Order 1234567890123 shipped"),
            )
        assertEquals("Card [card number] on file", out[0].text)
        assertEquals("Order 1234567890123 shipped", out[1].text)
        assertEquals(1, count)
    }

    @Test
    fun `masks cards in event text`() {
        assertEquals(
            "Paid with [card number]",
            Redactor.maskCards("Paid with 4111-1111-1111-1111"),
        )
        assertEquals("Your code is 123456", Redactor.maskCards("Your code is 123456"))
        assertEquals("Order 1234567890123", Redactor.maskCards("Order 1234567890123"))
    }

    @Test
    fun `luhn check`() {
        assertTrue(Redactor.luhn("4111111111111111"))
        assertTrue(Redactor.luhn("378282246310005"))
        assertFalse(Redactor.luhn("4111111111111112"))
    }

    @Test
    fun `masks find matches`() {
        val redactor = Redactor()
        val out =
            redactor.nodes(
                listOf(
                    node("e1", "edit", "hunter2", states = listOf("editable", "password")),
                    node("e2", text = "Card 4111 1111 1111 1111"),
                    node("e3", text = "Sign in"),
                )
            )
        assertEquals(2, redactor.count)
        assertFalse(out[0].text!!.contains("hunter2"))
        assertFalse(out[1].text!!.contains("4111 1111 1111 1111"))
        assertEquals("Sign in", out[2].text)
    }
}
