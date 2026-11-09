package com.fingertipsmcp.browser

/**
 * Selects tabs in a desktop browser's windows without bringing the browser in front of the person's
 * other apps, which every way DevTools has to select a tab does. FingertipsMCP for Chrome does it
 * through its viewer extension.
 */
interface TabSelector {
    /**
     * Makes the tab [id], a browser target id, the active tab in its window, and says whether it
     * did. False sends the caller to DevTools.
     */
    suspend fun select(id: String): Boolean

    /**
     * The browser target id of the active tab of the normal window the browser focused last, as the
     * browser itself knows it, or null when that is not known. A tab's own `visibilityState` can't
     * say this on a desktop: a selected tab in a window behind another app's reads `hidden`.
     */
    suspend fun showing(): String?
}

/**
 * How [BrowserServiceImpl] selects a tab, through [selector] where there is one. Without it, or
 * when it did not, DevTools selects the tab, which on a desktop brings the browser in front too.
 * [visible] says whether a tab's page reads `visible`: false for a closed tab, null when that can't
 * be told.
 */
internal class TabSelection(
    private val selector: TabSelector?,
    private val visible: suspend (String) -> Boolean?,
) {
    /** Whether [selector] made the tab [id] the one its window shows. */
    suspend fun quietly(id: String): Boolean = selector?.select(id) == true

    /**
     * Selects [id] for input or a show through [selector], and says whether it did. False leaves it
     * to `Page.bringToFront`, which brings Chrome in front. That is also what happens when Chrome's
     * window is not drawn, behind another app's window or minimized, as the page in its active tab
     * reads it: Chrome drops input into a tab it has not painted, and a show is for the tab to be
     * drawn, so the window has to come up, as Chrome's app does on the phone. Deciding before the
     * select costs a covered window no wait. Activating a tab needs it only selected, so [quietly]
     * never raises a window.
     */
    suspend fun forUse(id: String): Boolean {
        if (selector == null) return false
        val drawn = selector.showing()?.let { visible(it) }
        return drawn != false && quietly(id)
    }

    /**
     * Whether [id] is the tab Chrome shows. [selector] answers when it knows, since on a desktop
     * the selected tab of a window behind another app's reads `hidden`. Otherwise the page's
     * visibility answers, as on Android.
     */
    suspend fun showing(id: String): Boolean? {
        selector?.showing()?.let {
            return it == id
        }
        return visible(id)
    }

    /**
     * Opens a tab on [url] in [socket]'s browser, as [TabEvents.newTab] does, and returns its
     * Chrome id. One to be shown, not [background], opens hidden and is then selected through
     * [selector], since a tab Chrome opens in front brings a desktop Chrome in front of the
     * person's other apps. Without [selector], it opens in front, as it must on Android, whose
     * Chrome ignores `background`. When [selector] could not select it, `Target.activateTarget`
     * does, which brings Chrome in front as opening it in front would.
     */
    suspend fun newTab(
        events: TabEvents,
        socket: String,
        url: String,
        background: Boolean,
    ): String {
        if (background || selector == null) return events.newTab(url, background)
        val chromeId = events.newTab(url, background = true)
        if (!quietly(browserTargetId(socket, chromeId))) events.activate(chromeId)
        return chromeId
    }
}
