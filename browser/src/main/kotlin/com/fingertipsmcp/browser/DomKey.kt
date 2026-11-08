package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException

/** A key the page can receive through `Input.dispatchKeyEvent`. */
internal data class DomKey(
    val key: String,
    val code: String,
    val virtualKey: Int,
    val text: String?,
)

/**
 * Maps a [com.fingertipsmcp.model.KeyRequest] name onto a page key. Device keys such as HOME have
 * no page equivalent.
 */
internal fun domKey(name: String, metaState: Int): DomKey {
    val normalized = name.trim().uppercase().removePrefix("KEYCODE_")
    if (normalized.isEmpty()) throw ApiException.badRequest("A key name is required")
    if (normalized in SYSTEM_KEYS) {
        throw ApiException.badRequest(
            "$normalized is a device key. input=cdp only sends keys to the page; use input=touch."
        )
    }
    val base =
        NAMED[normalized]
            ?: letter(normalized)
            ?: digit(normalized)
            ?: throw ApiException.badRequest("Unknown key '$name'")
    return if (shifted(metaState) && base.text?.length == 1 && base.text[0].isLetter()) {
        val upper = base.text.uppercase()
        base.copy(key = upper, text = upper)
    } else {
        base
    }
}

/** Chrome's modifier bitfield: Alt=1, Ctrl=2, Meta=4, Shift=8. [metaState] is Android's. */
internal fun cdpModifiers(metaState: Int): Int {
    var modifiers = 0
    if (metaState and SHIFT != 0) modifiers = modifiers or 8
    if (metaState and ALT != 0) modifiers = modifiers or 1
    if (metaState and CTRL != 0) modifiers = modifiers or 2
    if (metaState and META != 0) modifiers = modifiers or 4
    return modifiers
}

private fun letter(name: String): DomKey? {
    if (name.length != 1 || name[0] !in 'A'..'Z') return null
    val vk = 'A'.code + (name[0] - 'A')
    val lower = name.lowercase()
    return DomKey(lower, "Key$name", vk, lower)
}

private fun digit(name: String): DomKey? {
    if (name.length != 1 || name[0] !in '0'..'9') return null
    val vk = '0'.code + (name[0] - '0')
    return DomKey(name, "Digit$name", vk, name)
}

private fun shifted(metaState: Int): Boolean = metaState and SHIFT != 0

private fun key(key: String, code: String, virtualKey: Int, text: String?) =
    DomKey(key, code, virtualKey, text)

private val NAMED =
    mapOf(
        "ENTER" to key("Enter", "Enter", 13, "\r"),
        "DEL" to key("Backspace", "Backspace", 8, null),
        "BACKSPACE" to key("Backspace", "Backspace", 8, null),
        "FORWARD_DEL" to key("Delete", "Delete", 46, null),
        "TAB" to key("Tab", "Tab", 9, null),
        "ESCAPE" to key("Escape", "Escape", 27, null),
        "ESC" to key("Escape", "Escape", 27, null),
        "SPACE" to key(" ", "Space", 32, " "),
        "DPAD_UP" to key("ArrowUp", "ArrowUp", 38, null),
        "UP" to key("ArrowUp", "ArrowUp", 38, null),
        "DPAD_DOWN" to key("ArrowDown", "ArrowDown", 40, null),
        "DOWN" to key("ArrowDown", "ArrowDown", 40, null),
        "DPAD_LEFT" to key("ArrowLeft", "ArrowLeft", 37, null),
        "LEFT" to key("ArrowLeft", "ArrowLeft", 37, null),
        "DPAD_RIGHT" to key("ArrowRight", "ArrowRight", 39, null),
        "RIGHT" to key("ArrowRight", "ArrowRight", 39, null),
        "MOVE_HOME" to key("Home", "Home", 36, null),
        "MOVE_END" to key("End", "End", 35, null),
        "PAGE_UP" to key("PageUp", "PageUp", 33, null),
        "PAGE_DOWN" to key("PageDown", "PageDown", 34, null),
    )

private val SYSTEM_KEYS =
    setOf(
        "BACK",
        "HOME",
        "RECENTS",
        "APP_SWITCH",
        "NOTIFICATIONS",
        "QUICK_SETTINGS",
        "POWER",
        "POWER_DIALOG",
        "LOCK",
        "LOCK_SCREEN",
        "SCREENSHOT",
    )

/** Android meta-state masks, including the left and right variants. */
private const val SHIFT = 1 or 64 or 128
private const val ALT = 2 or 16 or 32
private const val CTRL = 4_096 or 8_192 or 16_384
private const val META = 65_536 or 131_072 or 262_144
