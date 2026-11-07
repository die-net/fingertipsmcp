package net.die.phoneapi.helper.tree

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import net.die.phoneapi.model.TreeFocusedText

/**
 * Selects the text of [focused], which this recycles, and says how long it was. A node that takes
 * no text says nothing about a field, so it gives no length. Chrome's page with its nodes off is
 * one, and reading it as an empty field made `clear` report success.
 */
internal fun selectAll(focused: AccessibilityNodeInfo?): TreeFocusedText {
    val node = focused ?: return TreeFocusedText()
    try {
        if (!node.isEditable) return TreeFocusedText()
        val length = if (node.isShowingHintText) 0 else node.text?.length ?: 0
        if (length == 0) return TreeFocusedText(0)
        val args =
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
            }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        return TreeFocusedText(length)
    } finally {
        NodeCompat.recycle(node)
    }
}
