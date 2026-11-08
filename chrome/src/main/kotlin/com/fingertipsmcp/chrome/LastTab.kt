package com.fingertipsmcp.chrome

import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.model.ActionResult

/**
 * Keeps a window open when the API closes the last tab, as a person opens a new tab before closing
 * their last one. Chrome on Linux exits when its last window closes, which would end the session's
 * cookies and logins; on macOS it would sit with no window, which a person's Chrome rarely does.
 * Every other call goes straight to [browser].
 */
class LastTab(private val browser: BrowserService) : BrowserService by browser {
    override suspend fun closeTab(id: String): ActionResult {
        val pages = browser.targets().filter { it.type == "page" }
        if (pages.size == 1 && pages.single().id == id) browser.openTab("about:blank")
        return browser.closeTab(id)
    }
}
