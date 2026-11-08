package com.fingertipsmcp

import com.fingertipsmcp.browser.CHROME_PACKAGE
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.DeviceStateSummary
import com.fingertipsmcp.model.FindRequest
import com.fingertipsmcp.model.FindResult
import com.fingertipsmcp.model.NodeSelector
import com.fingertipsmcp.model.ScreenState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Whether Chrome shows a page's dialog, read from the accessibility tree. Chrome draws an alert,
 * confirm, or prompt in its own views, whose ids may change between releases; that only loses what
 * this tells.
 *
 * Background work asks this, such as a wait or a DevTools call that timed out, so it never wakes
 * the screen or extends the awake lease, as a screen call's find would. It reads the tree with
 * [find] directly, and only while [state] says the screen is on and unlocked.
 */
internal class ChromeDialog(
    private val state: () -> DeviceStateSummary,
    private val find: (FindRequest) -> FindResult,
    private val io: CoroutineDispatcher,
) {
    /**
     * Whether Chrome shows a dialog, and with [host], one whose title names that whole host, as
     * "example.com says" does and "www.example.com says" and "example.com:8080 says" do not. Null
     * when it cannot tell: the screen is off or locked, so nothing of Chrome is on screen to read,
     * or the helper could not read the tree.
     */
    suspend fun shown(host: String? = null): Boolean? {
        val now = state()
        if (now.screen != ScreenState.ON || now.keyguard.locked) return null
        return try {
            withContext(io) {
                has(NodeSelector(id = DIALOG_ID, packageName = CHROME_PACKAGE)) &&
                    (host == null ||
                        has(
                            NodeSelector(
                                id = TITLE_ID,
                                textRegex = wholeHost(host),
                                packageName = CHROME_PACKAGE,
                            )
                        ))
            }
        } catch (_: ApiException) {
            null
        }
    }

    private fun has(selector: NodeSelector): Boolean =
        find(FindRequest(selector, limit = 1)).matches.isNotEmpty()

    /**
     * A pattern for [host] with no more of a host on either side. The words around it are Chrome's
     * and change with its language, so they are not matched. The characters are spelled out because
     * `\w` on Android takes in every script's letters, and those can touch the host in a title.
     */
    private fun wholeHost(host: String): String =
        "(?<!$HOST_CHAR)${Regex.escape(host)}(?!$HOST_CHAR)"

    private companion object {
        /** The view Chrome draws a page's dialog in. */
        const val DIALOG_ID = "tab_modal_dialog_container"

        /** The dialog's title, which names the page's host. */
        const val TITLE_ID = "title"

        /** A character that, next to a host, makes it part of a longer host or adds a port. */
        const val HOST_CHAR = "[A-Za-z0-9.:-]"
    }
}
