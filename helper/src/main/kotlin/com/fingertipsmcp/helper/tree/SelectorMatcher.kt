package com.fingertipsmcp.helper.tree

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.NodeSelector
import com.fingertipsmcp.model.UiNode

/** Matches converted nodes against a [NodeSelector]; every non-null field must match. */
class SelectorMatcher(private val selector: NodeSelector) {
    private val regex: Regex? =
        selector.textRegex?.let {
            try {
                Regex(it)
            } catch (e: IllegalArgumentException) {
                throw ApiException.badRequest("Invalid textRegex: ${e.message.orEmpty()}", e)
            }
        }

    /** True when the selector has no criteria besides `ref` and `index`. */
    val isEmpty: Boolean =
        with(selector) {
            listOf(text, textContains, textRegex, desc, descContains, id, role, packageName).all {
                it == null
            } && clickable == null && editable == null && focused == null
        }

    fun matches(node: UiNode, packageName: String?): Boolean =
        with(selector) {
            equalsText(text, node.text) &&
                contains(textContains, node.text) &&
                matchesRegex(node) &&
                equalsText(desc, node.desc) &&
                contains(descContains, node.desc) &&
                matchesId(id, node.id) &&
                equalsText(role, node.role) &&
                (this.packageName == null || this.packageName == packageName) &&
                flag(clickable, UiStates.CLICKABLE in node.states) &&
                flag(editable, UiStates.EDITABLE in node.states) &&
                flag(focused, UiStates.FOCUSED in node.states)
        }

    private fun matchesRegex(node: UiNode): Boolean {
        val r = regex ?: return true
        return node.text?.let(r::containsMatchIn) == true ||
            node.desc?.let(r::containsMatchIn) == true
    }

    private fun equalsText(expected: String?, actual: String?) =
        expected == null || actual?.trim().equals(expected.trim(), ignoreCase = true)

    private fun contains(expected: String?, actual: String?) =
        expected == null || actual?.contains(expected, ignoreCase = true) == true

    private fun flag(expected: Boolean?, actual: Boolean) = expected == null || expected == actual

    private fun matchesId(expected: String?, actual: String?): Boolean {
        if (expected == null) return true
        if (actual == null) return false
        return actual == expected ||
            actual == expected.substringAfter(":id/") ||
            actual.substringAfter(":id/") == expected
    }
}
