package com.fingertipsmcp.helper.tree

import com.fingertipsmcp.model.UiNode
import com.fingertipsmcp.model.UiWindow

/**
 * Masks secrets in snapshot windows: the text of password fields and one-time-code fields, and
 * anything that looks like a card number. This keeps secrets out of an agent's context in the
 * outline. It is not a security boundary, since a screenshot still shows them.
 */
internal class Redactor {
    /** How many values were masked. */
    var count = 0
        private set

    fun windows(windows: List<UiWindow>): List<UiWindow> = windows.map { window ->
        window.root?.let { window.copy(root = node(it)) } ?: window
    }

    /** Masks find matches, which come without the siblings that label web form fields. */
    fun nodes(nodes: List<UiNode>): List<UiNode> = nodes.map { node(it) }

    /**
     * [label] is the text just before [node] in its parent, which is how web forms label fields.
     */
    private fun node(node: UiNode, label: String? = null): UiNode {
        var previous: String? = null
        val children =
            node.children.map { child ->
                node(child, previous).also { previous = labelText(child) ?: previous }
            }
        val text = node.text?.let { text(node, it, label) }
        val desc = node.desc?.let(::cards)
        if (text == node.text && desc == node.desc && children == node.children) return node
        return node.copy(text = text, desc = desc, children = children)
    }

    private fun text(node: UiNode, text: String, label: String?): String {
        val secret = UiStates.PASSWORD in node.states || codeField(node, text, label)
        if (!secret) return cards(text)
        if (text.isBlank() || text == node.hint) return text
        count++
        return MASK
    }

    private fun cards(text: String): String = maskCards(text) { count++ }

    private fun codeField(node: UiNode, text: String, label: String?): Boolean {
        if (UiStates.EDITABLE !in node.states && node.role != "edit") return false
        val names = listOfNotNull(node.id, node.hint, node.desc, label)
        if (names.any { CODE_FIELD.containsMatchIn(it) }) return true
        return DIGITS.matches(text.trim()) &&
            names.any { SHORT_CODE.containsMatchIn(it) && !NOT_CODE.containsMatchIn(it) }
    }

    /** Text a field's label could come from: a non-editable node's text, or its first child's. */
    private fun labelText(node: UiNode): String? {
        if (UiStates.EDITABLE in node.states) return null
        return node.text?.takeIf { it.isNotBlank() }
            ?: node.children.firstOrNull()?.let(::labelText)
    }

    companion object {
        const val MASK = "[redacted]"
        const val CARD_MASK = "[card number]"
        private val CARD = Regex("""(?<!\d)\d(?:[ -]?\d){12,18}(?!\d)""")
        private val DIGITS = Regex("""\d{4,8}""")
        private val SHORT_CODE = Regex("""\b(code|pin)\b""", RegexOption.IGNORE_CASE)
        private val NOT_CODE =
            Regex(
                """zip|postal|post code|promo|coupon|gift|discount|referral|area""",
                RegexOption.IGNORE_CASE,
            )
        private val CODE_FIELD =
            Regex(
                """otp|one[ _-]?time|passcode|2fa|mfa|""" +
                    """(verification|security|confirmation|sign[ _-]?in|login|access|auth\w*|sms)[ _-]?code""",
                RegexOption.IGNORE_CASE,
            )

        /**
         * Masks the card numbers in [text]: runs of 13 to 19 digits that pass the Luhn check.
         * [onMask] runs once for each.
         */
        fun maskCards(text: String, onMask: () -> Unit = {}): String =
            CARD.replace(text) { match ->
                if (luhn(match.value.filter(Char::isDigit))) {
                    onMask()
                    CARD_MASK
                } else {
                    match.value
                }
            }

        /** Whether [digits] passes the Luhn check card numbers carry. */
        fun luhn(digits: String): Boolean {
            var sum = 0
            for ((i, ch) in digits.reversed().withIndex()) {
                var d = ch - '0'
                if (i % 2 == 1) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
            }
            return sum % 10 == 0
        }
    }
}
