package net.die.phoneapi.helper.tree

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import net.die.phoneapi.model.PinpadKeys
import net.die.phoneapi.model.Rect as ModelRect

/** Reads the lock screen's PIN keypad out of the accessibility tree. */
internal object PinPad {
    private const val MAX_NODES = 400
    private const val MIN_KEY_PX = 16

    /**
     * The keypad currently on screen; empty when the bouncer isn't showing. Blocking IPC.
     *
     * The field's text changes with no event that has to reach the accessibility cache, so
     * [refresh] reads each node from the keyguard. A caller that has cleared the cache doesn't need
     * it.
     */
    fun keys(windows: List<AccessibilityWindowInfo>, refresh: Boolean): PinpadKeys {
        val digits = HashMap<String, ModelRect>()
        var submit: ModelRect? = null
        var delete: ModelRect? = null
        var entered: Int? = null
        for (window in windows.sortedByDescending { it.layer }) {
            val root = window.root ?: continue
            val found = collect(root, refresh)
            if (entered == null) entered = found.entered
            for (key in found.keys) {
                val digit = PinKeys.digitOf(key.id, key.text, key.desc)
                when {
                    digit != null -> digits.putIfAbsent(digit.toString(), key.bounds)
                    PinKeys.isSubmit(key.id, key.text, key.desc) -> submit = submit ?: key.bounds
                    PinKeys.isDelete(key.id, key.text, key.desc) -> delete = delete ?: key.bounds
                }
            }
        }
        return PinpadKeys(digits, submit, delete, entered)
    }

    private data class Candidate(
        val id: String?,
        val text: String?,
        val desc: String?,
        val bounds: ModelRect,
    )

    /** A window's clickable keys, and the length of its password field if it has one. */
    private data class Found(val keys: List<Candidate>, val entered: Int?)

    private fun collect(root: AccessibilityNodeInfo, refresh: Boolean): Found {
        val keys = ArrayList<Candidate>()
        var entered: Int? = null
        val bounds = Rect()
        visible(root, refresh) { node ->
            // Only the length leaves here. The text can show the last digit typed.
            if (entered == null && node.isPassword) entered = node.text?.length ?: 0
            candidate(node, bounds)?.let(keys::add)
        }
        return Found(keys, entered)
    }

    /** Calls [visit] on each node visible to the user, up to [MAX_NODES] read. */
    private inline fun visible(
        root: AccessibilityNodeInfo,
        refresh: Boolean,
        visit: (AccessibilityNodeInfo) -> Unit,
    ) {
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var read = 0
        while (stack.isNotEmpty() && read < MAX_NODES) {
            val node = stack.removeLast()
            read++
            if (refresh) node.refresh()
            if (node.isVisibleToUser) {
                visit(node)
                for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::add)
            }
            NodeCompat.recycle(node)
        }
        stack.forEach(NodeCompat::recycle)
    }

    /** [node] as a key, when it is clickable and big enough to be one. */
    private fun candidate(node: AccessibilityNodeInfo, bounds: Rect): Candidate? {
        if (!node.isClickable) return null
        node.getBoundsInScreen(bounds)
        if (bounds.width() < MIN_KEY_PX || bounds.height() < MIN_KEY_PX) return null
        return Candidate(
            id = node.viewIdResourceName,
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            bounds = bounds.toModel(),
        )
    }
}

/**
 * Classifies lock-screen keys from their resource id and labels. AOSP's bouncer uses
 * `com.android.systemui:id/key1`..`key0` with the digit as the content description, and
 * `delete_button`; OEM skins usually keep the labels even when the ids differ, and some add a
 * letter group ("2 ABC").
 */
internal object PinKeys {
    private val ID_DIGIT = Regex("""key([0-9])$""")
    private val LABEL_DIGIT = Regex("""^([0-9])(\s.*)?$""")
    private val SUBMIT_LABELS = setOf("enter", "ok", "done", "submit", "confirm", "unlock")
    private val DELETE_LABELS = setOf("delete", "backspace")

    /** The digit a key enters, or null when it isn't a digit key. */
    fun digitOf(id: String?, text: String?, desc: String?): Char? {
        val byId = id?.substringAfterLast('/')?.let(ID_DIGIT::find)
        if (byId != null) return byId.groupValues[1][0]
        for (label in listOfNotNull(desc, text)) {
            val byLabel = LABEL_DIGIT.find(label.trim())
            if (byLabel != null) return byLabel.groupValues[1][0]
        }
        return null
    }

    fun isSubmit(id: String?, text: String?, desc: String?): Boolean {
        val name = id?.substringAfterLast('/')
        if (name == "key_enter" || name == "eca_submit") return true
        return listOfNotNull(desc, text).any { it.trim().lowercase() in SUBMIT_LABELS }
    }

    fun isDelete(id: String?, text: String?, desc: String?): Boolean {
        val name = id?.substringAfterLast('/')
        if (name == "delete_button" || name == "key_delete") return true
        return listOfNotNull(desc, text).any { it.trim().lowercase() in DELETE_LABELS }
    }
}
