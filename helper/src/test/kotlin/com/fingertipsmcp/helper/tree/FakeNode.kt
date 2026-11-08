package com.fingertipsmcp.helper.tree

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** A node as UiAutomation hands one over, built from what a test sets. */
internal class FakeNode(
    private val kind: String = "android.widget.FrameLayout",
    private val id: String? = null,
    private val description: String? = null,
    private val bounds: Rect = Rect(0, 0, 1080, 2000),
    private val editable: Boolean = false,
    private val shownText: String? = null,
    private val children: List<FakeNode> = emptyList(),
) : AccessibilityNodeInfo() {
    /** The actions performed on this node, in order. */
    val actions = ArrayList<Int>()

    override fun getClassName(): CharSequence = kind

    override fun getViewIdResourceName(): String? = id

    override fun getContentDescription(): CharSequence? = description

    override fun getBoundsInScreen(outBounds: Rect) = outBounds.set(bounds)

    override fun isVisibleToUser(): Boolean = true

    override fun isEditable(): Boolean = editable

    override fun isShowingHintText(): Boolean = false

    override fun getText(): CharSequence? = shownText

    override fun getChildCount(): Int = children.size

    override fun getChild(index: Int): AccessibilityNodeInfo = children[index]

    override fun performAction(action: Int, arguments: Bundle?): Boolean {
        actions += action
        return true
    }
}
