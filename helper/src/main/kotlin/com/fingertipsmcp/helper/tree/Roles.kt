package com.fingertipsmcp.helper.tree

/** The node facts that decide its simplified role. */
data class RoleHints(
    val className: String?,
    val roleDescription: String? = null,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val heading: Boolean = false,
    val collection: Boolean = false,
    val collectionItem: Boolean = false,
)

/** Maps widget class names and role descriptions to the short roles used in snapshots. */
object Roles {
    const val VIEW = "view"

    private val BY_CLASS =
        mapOf(
            "EditText" to "edit",
            "AutoCompleteTextView" to "edit",
            "MultiAutoCompleteTextView" to "edit",
            "SearchView" to "search",
            "Switch" to "switch",
            "SwitchCompat" to "switch",
            "SwitchMaterial" to "switch",
            "MaterialSwitch" to "switch",
            "ToggleButton" to "switch",
            "CheckBox" to "check",
            "CheckedTextView" to "check",
            "RadioButton" to "radio",
            "Chip" to "button",
            "ImageView" to "image",
            "TextView" to "text",
            "WebView" to "web",
            "GridView" to "grid",
            "SeekBar" to "slider",
            "RatingBar" to "slider",
            "Slider" to "slider",
            "ProgressBar" to "progress",
            "Spinner" to "spinner",
            "ViewPager" to "pager",
            "ViewPager2" to "pager",
            "TabWidget" to "tabs",
            "TabLayout" to "tabs",
            "TabView" to "tab",
            "Toolbar" to "toolbar",
        )

    /** Checked in order, so more specific suffixes come first. */
    private val SUFFIXES =
        listOf(
            "EditText" to "edit",
            "Switch" to "switch",
            "CheckBox" to "check",
            "RadioButton" to "radio",
            "Button" to "button",
            "ImageView" to "image",
            "CheckedTextView" to "check",
            "TextView" to "text",
            "RecyclerView" to "list",
            "ListView" to "list",
            "ScrollView" to "scroll",
            "WebView" to "web",
        )

    private val BY_ROLE_DESCRIPTION =
        mapOf(
            "button" to "button",
            "switch" to "switch",
            "toggle" to "switch",
            "checkbox" to "check",
            "check box" to "check",
            "radio button" to "radio",
            "tab" to "tab",
            "link" to "link",
            "heading" to "heading",
            "image" to "image",
            "slider" to "slider",
            "dropdown list" to "spinner",
        )

    fun roleOf(hints: RoleHints): String {
        hints.roleDescription?.lowercase()?.let(BY_ROLE_DESCRIPTION::get)?.let {
            return it
        }
        if (hints.editable) return "edit"
        val simple = hints.className?.substringAfterLast('.')?.substringAfterLast('$')
        if (simple != null) {
            val byClass =
                BY_CLASS[simple]
                    ?: SUFFIXES.firstOrNull { (suffix, _) -> simple.endsWith(suffix) }?.second
            if (byClass != null) return byClass
        }
        return when {
            hints.checkable -> "check"
            hints.heading -> "heading"
            hints.collection -> "list"
            hints.scrollable -> "scroll"
            hints.clickable -> "button"
            hints.collectionItem -> "item"
            else -> VIEW
        }
    }
}
