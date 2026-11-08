package com.fingertipsmcp.mcp

import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.server.BrowserInputPolicy
import com.fingertipsmcp.server.Product

/**
 * The overview a client gets at `initialize`: that the phone is shared and parallel use is normal,
 * that browser work starts in the background, how the screen is shared, and how to work in few
 * calls. The tool descriptions carry the details, since some clients never show these instructions.
 * Lines about tools [scopes] leave out are left out.
 */
internal fun mcpInstructions(scopes: Set<Scope>, product: Product = Product.ANDROID): String =
    buildList {
        val browser = Scope.BROWSER in scopes
        val phone = product.browserInput == BrowserInputPolicy.PHONE
        add(
            "FingertipsMCP drives ${product.drives} that other agents, scripts, and people use " +
                "at the same time. That is the normal case" +
                if (browser) {
                    ": browser work runs in the background, side by side, and only the screen is " +
                        "taken in turns."
                } else {
                    ", and the screen is taken in turns."
                }
        )
        if (browser) add(backgroundLine(phone))
        add(
            "- Tools that use the screen wait up to 60 s for it, then fail with screen_busy. Call " +
                "screen_release when you are done with it, or to hand it to a person, such as for " +
                "a captcha."
        )
        add(
            "- Give each subagent or parallel task that uses the ${if (phone) "phone" else "browser"} " +
                "its own session with " +
                "session_start. Never pass session on your own calls."
        )
        add("")
        add(
            "Each call re-reads your whole context, so the number of calls costs more than the size " +
                "of each result. " +
                if (Scope.OBSERVE in scopes && phone) {
                    "One step is an action and one wait_for with snapshot or screenshot true. "
                } else {
                    "One step is an action and one wait_for on a browser condition. "
                } +
                "Act by selector rather than taking a snapshot first."
        )
    }
    .joinToString("\n")

/** How browser work shares the screen: on a phone, input can run in the background too. */
private fun backgroundLine(phone: Boolean): String =
    if (phone) {
        "- Start browser work in the background, unless you already know the page does not " +
            "work there. Take the screen only for what needs it, such as apps or a captcha."
    } else {
        "- Reading and scripts run in the background. Clicks, scrolls, and typing use the " +
            "screen, which here is the browser's one mouse and keyboard."
    }
