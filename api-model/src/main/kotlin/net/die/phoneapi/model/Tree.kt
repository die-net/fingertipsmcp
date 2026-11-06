package net.die.phoneapi.model

import kotlinx.serialization.Serializable

// Bodies of the app's tree calls to the helper (IHelper.tree). The app encodes them and the
// helper decodes them, so both sides share one definition.

/** Every tree call's payload: the screen size in pixels, and the op's own [body] as JSON. */
@Serializable
public data class TreeScreened(val widthPx: Int, val heightPx: Int, val body: String = "{}")

/** The `snapshot` op's body. */
@Serializable
public data class TreeSnapshotCall(val options: SnapshotOptions, val summary: DeviceStateSummary)

/** The `act` op's body: a node action on [ref], with [text] for actions that take one. */
@Serializable
public data class TreeActCall(val ref: String, val action: String, val text: String? = null)

/** The `touchTarget` op's body. [force] returns the visible part even when something covers it. */
@Serializable public data class TreeTouchCall(val ref: String, val force: Boolean = false)

/** The `global` op's body: an `AccessibilityService.GLOBAL_ACTION_*` value. */
@Serializable public data class TreeGlobalCall(val action: Int)

/** The `setText` op's body. */
@Serializable
public data class TreeSetTextCall(
    val text: String,
    val clear: Boolean = false,
    val submit: Boolean = false,
)

/** The `focus` op's body. */
@Serializable public data class TreeFocusCall(val selector: NodeSelector? = null)

/** The `contentBounds` op's body. */
@Serializable public data class TreePackageCall(val packageName: String = "")

/** The `windowTitle` op's body: text to look for in window titles. */
@Serializable public data class TreeTitleCall(val text: String)

/** The `windowTitle` op's reply. */
@Serializable public data class TreeFound(val found: Boolean)

/** The `selectFocused` op's reply: the focused field's text length, or null with no such field. */
@Serializable public data class TreeFocusedText(val length: Int? = null)
