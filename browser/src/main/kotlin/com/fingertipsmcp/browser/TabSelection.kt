package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
     * say this on a desktop: a selected tab in a minimized window reads `hidden`, as does one in a
     * window behind another app's unless Chrome runs with
     * `--disable-backgrounding-occluded-windows`.
     */
    suspend fun showing(): String?
}

/**
 * How [BrowserServiceImpl] selects a tab, through [selector] where there is one. Without it, or
 * when it did not, DevTools selects the tab, which on a desktop brings the browser in front too.
 * [restore] restores a tab's window when it is minimized and says whether it did. [visible] says
 * whether a tab's page reads `visible`: false for a closed tab, null when that can't be told.
 */
internal class TabSelection(
    private val selector: TabSelector?,
    private val restore: suspend (String) -> Boolean,
    private val visible: suspend (String) -> Boolean?,
) {
    /** Whether [selector] made the tab [id] the one its window shows. */
    suspend fun quietly(id: String): Boolean = selector?.select(id) == true

    /**
     * Selects [id] for input or a show through [selector], and says whether it did. Chrome drops
     * input into a tab it has not painted, and a show is for the tab to be drawn, so the tab's
     * window has to be drawn. A minimized window is restored first, which on macOS leaves the app
     * in front where it is, unlike `Page.bringToFront`. False leaves it to `Page.bringToFront`,
     * which raises the window and brings Chrome in front. That is what happens when [selector] did
     * not select the tab, and when the window Chrome shows is not drawn though it is not minimized,
     * as the page in its active tab reads it: one behind another app's window in a Chrome started
     * without `--disable-backgrounding-occluded-windows`. Deciding before the select costs such a
     * window no wait. Activating a tab needs it only selected, so [quietly] never restores or
     * raises a window.
     */
    suspend fun forUse(id: String): Boolean {
        if (selector == null) return false
        if (restore(id)) return quietly(id)
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

/**
 * Restores the window of the tab [id] on [devtools] with `Browser.setWindowBounds` when it is
 * minimized, and says whether it did. On macOS that leaves the app in front where it is, though the
 * window comes up over other windows, where `Page.bringToFront` brings Chrome in front. On Windows
 * both bring Chrome in front only when it has no other window open. False too when Chrome refuses,
 * as for a tab with no window, since then there is nothing this can restore.
 */
internal suspend fun restoreMinimized(devtools: DevtoolsEndpoints, id: String): Boolean {
    val (socket, chromeId) = parseBrowserTargetId(id)
    return try {
        devtools.withBrowser(socket) { cdp ->
            val window =
                cdp.call(
                        "Browser.getWindowForTarget",
                        buildJsonObject { put("targetId", chromeId) },
                    )
                    .decodeCdp<TargetWindow>()
            if (window?.bounds?.windowState != MINIMIZED) return@withBrowser false
            cdp.call(
                "Browser.setWindowBounds",
                buildJsonObject {
                    put("windowId", window.windowId)
                    putJsonObject("bounds") { put("windowState", "normal") }
                },
            )
            true
        }
    } catch (e: ApiException) {
        if (e.rejected == null) throw e
        false
    }
}

/** `Browser.getWindowForTarget`'s answer. */
@Serializable private data class TargetWindow(val windowId: Int, val bounds: WindowState? = null)

@Serializable private data class WindowState(val windowState: String? = null)

private const val MINIMIZED = "minimized"
