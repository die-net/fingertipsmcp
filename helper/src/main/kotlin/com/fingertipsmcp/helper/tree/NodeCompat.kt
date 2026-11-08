package com.fingertipsmcp.helper.tree

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo

/** Object-pool handling that only matters below Android 13, where nodes must be recycled. */
internal object NodeCompat {
    private val pooled = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    // recycle() is a no-op from API 33 but still required below it.
    @Suppress("DEPRECATION")
    fun recycle(node: AccessibilityNodeInfo) {
        if (pooled) node.recycle()
    }

    @Suppress("DEPRECATION") // Like recycle(node), a no-op from API 33.
    fun recycle(window: AccessibilityWindowInfo) {
        if (pooled) window.recycle()
    }

    fun recycleAll(windows: List<AccessibilityWindowInfo>) {
        if (pooled) windows.forEach(::recycle)
    }

    /** Checked, counting "partially checked" (API 36+) as checked. */
    fun isChecked(node: AccessibilityNodeInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            node.checked != AccessibilityNodeInfo.CHECKED_STATE_FALSE
        } else {
            legacyChecked(node)
        }

    // Replaced by the tri-state getChecked() in API 36.
    @Suppress("DEPRECATION") private fun legacyChecked(node: AccessibilityNodeInfo) = node.isChecked

    fun copy(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AccessibilityNodeInfo(node)
        else obtainCopy(node)

    // obtain() is the only copy API below 33.
    @Suppress("DEPRECATION")
    private fun obtainCopy(node: AccessibilityNodeInfo) = AccessibilityNodeInfo.obtain(node)
}

/** Short names for standard actions, and back to the platform action objects. */
internal object ActionCatalog {
    private val standard: List<Pair<String, AccessibilityAction>> = buildList {
        add(ActionNames.CLICK to AccessibilityAction.ACTION_CLICK)
        add(ActionNames.LONG_CLICK to AccessibilityAction.ACTION_LONG_CLICK)
        add(ActionNames.FOCUS to AccessibilityAction.ACTION_FOCUS)
        add(ActionNames.CLEAR_FOCUS to AccessibilityAction.ACTION_CLEAR_FOCUS)
        add(ActionNames.SELECT to AccessibilityAction.ACTION_SELECT)
        add("clearSelection" to AccessibilityAction.ACTION_CLEAR_SELECTION)
        add("accessibilityFocus" to AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        add("clearAccessibilityFocus" to AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
        add("nextAtMovementGranularity" to AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
        add(
            "previousAtMovementGranularity" to
                AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
        )
        add("nextHtmlElement" to AccessibilityAction.ACTION_NEXT_HTML_ELEMENT)
        add("previousHtmlElement" to AccessibilityAction.ACTION_PREVIOUS_HTML_ELEMENT)
        add(ActionNames.SCROLL_FORWARD to AccessibilityAction.ACTION_SCROLL_FORWARD)
        add(ActionNames.SCROLL_BACKWARD to AccessibilityAction.ACTION_SCROLL_BACKWARD)
        add("scrollUp" to AccessibilityAction.ACTION_SCROLL_UP)
        add("scrollDown" to AccessibilityAction.ACTION_SCROLL_DOWN)
        add("scrollLeft" to AccessibilityAction.ACTION_SCROLL_LEFT)
        add("scrollRight" to AccessibilityAction.ACTION_SCROLL_RIGHT)
        add("scrollToPosition" to AccessibilityAction.ACTION_SCROLL_TO_POSITION)
        add("pageUp" to AccessibilityAction.ACTION_PAGE_UP)
        add("pageDown" to AccessibilityAction.ACTION_PAGE_DOWN)
        add("pageLeft" to AccessibilityAction.ACTION_PAGE_LEFT)
        add("pageRight" to AccessibilityAction.ACTION_PAGE_RIGHT)
        add("copy" to AccessibilityAction.ACTION_COPY)
        add("paste" to AccessibilityAction.ACTION_PASTE)
        add("cut" to AccessibilityAction.ACTION_CUT)
        add("setSelection" to AccessibilityAction.ACTION_SET_SELECTION)
        add(ActionNames.EXPAND to AccessibilityAction.ACTION_EXPAND)
        add(ActionNames.COLLAPSE to AccessibilityAction.ACTION_COLLAPSE)
        add(ActionNames.DISMISS to AccessibilityAction.ACTION_DISMISS)
        add(ActionNames.SET_TEXT to AccessibilityAction.ACTION_SET_TEXT)
        add(ActionNames.SHOW_ON_SCREEN to AccessibilityAction.ACTION_SHOW_ON_SCREEN)
        add("contextClick" to AccessibilityAction.ACTION_CONTEXT_CLICK)
        add(ActionNames.SET_PROGRESS to AccessibilityAction.ACTION_SET_PROGRESS)
        add("moveWindow" to AccessibilityAction.ACTION_MOVE_WINDOW)
        add("showTooltip" to AccessibilityAction.ACTION_SHOW_TOOLTIP)
        add("hideTooltip" to AccessibilityAction.ACTION_HIDE_TOOLTIP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add("pressAndHold" to AccessibilityAction.ACTION_PRESS_AND_HOLD)
            add(ActionNames.IME_ENTER to AccessibilityAction.ACTION_IME_ENTER)
        }
    }

    private val byName: Map<String, AccessibilityAction> = standard.associate { (name, action) ->
        name.lowercase() to action
    }

    private val nameById: Map<Int, String> = standard.associate { (name, action) ->
        action.id to name
    }

    fun forName(name: String): AccessibilityAction? = byName[name.lowercase()]

    fun names(node: AccessibilityNodeInfo): List<String> =
        node.actionList.mapNotNull { action ->
            nameById[action.id] ?: action.label?.toString()?.takeIf { it.isNotBlank() }
        }

    /** A custom action (one an app adds with a label) matching [label]. */
    fun custom(node: AccessibilityNodeInfo, label: String): AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.id !in nameById && action.label?.toString().equals(label, ignoreCase = true)
        }
}
