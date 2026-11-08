package com.fingertipsmcp.mcp

/**
 * FingertipsMCP for Chrome's wording for tools whose phone description does not fit a desktop
 * browser. The arguments and behavior are the same; only what the agent reads differs. Tools not
 * listed keep their text.
 */
val DESKTOP_TOOL_DESCRIPTIONS: Map<String, String> =
    mapOf(
        "device_info" to
            "The computer and its browser: OS, version, display, capabilities, and who has the screen. Cheap. The screen here is the browser's mouse and keyboard, which one client uses at a time. Other agents, scripts, and people may use the browser at the same time, which is normal. screenHolder says who has the screen now (you; parent, the session that started yours; subagent, one you started with session_start; agent; or script; absent when it is free), and screenHeldMs for how long. Tools whose descriptions say Uses the screen wait for it.",
        "session_start" to
            "Give a subagent or parallel task its own place in line for the screen and its own browser tabs, so parallel tasks do not click over each other. Not needed for one agent working step by step. Call it once per task, either yourself, putting the id in the subagent's instructions, or in the subagent. Then pass the id as session on every call for that task, reads included; a call without session acts as you, with your screen turn and your tabs. Tabs are not shared between sessions. End it with session_end when the task is done; it also ends when your connection ends, or after 20 minutes unused.",
        "screen_release" to
            "Let the screen, the browser's mouse and keyboard, go when you are done with it for now, so other clients can use it. The browser goes back to a blank tab, so yours is hidden until you act on it. Your tabs stay yours and background browser work keeps going. The next call that needs the screen takes it again, waiting if someone else has it. Holding the screen idle while another client waits also lets it go, after 30 s. When a person has to step in, such as for a captcha you cannot solve, call this with cleanup false, tell them which tab to finish in, and wait_for a browser.* condition on your tab for the page after it.",
        "wait_for" to
            "Wait until all or any browser conditions match. Pass at least one. Use it after an action instead of sleeping or taking a screenshot. A condition's type is browser.url, browser.lifecycle, browser.networkIdle, browser.element, browser.request, browser.dialog, browser.newTarget, browser.log, or browser.settled; the phone's node, window, ime, idle, screen, and keyguard conditions are not available, nor is snapshot true. screenshot true adds a screenshot of the tab showing. After browser_open, wait for browser.url and browser.settled on its tab: pass the tab as target, once at the top level or on each condition. To check a browser_tap, wait for browser.element with a CSS selector for the new state rather than running browser_eval. Pass target when more than one page is open.",
        "browser_targets" to
            "List your tabs (owned true) and tabs no session owns (owned false), with id, title, and url. Other sessions' tabs are not listed, and a session's tabs close when it ends. Chrome updates the list itself, so a closed tab or a redirect can lag behind.",
        "browser_open" to
            "Open a tab and return its target, for every other browser tool. The tab is yours: other sessions cannot see it, and it closes when your session ends. Without the screen it opens hidden, in the background, and reading and scripts work there. With the screen, Chrome shows it. url must be http(s) or about:blank. Follow with wait_for on browser.url and browser.settled. To only read or search a page, browser_read or browser_find with url opens it for you. incognito is not available.",
        "browser_tap" to
            "Click a snapshot ref or a CSS selector in a browser target. A selector waits up to waitMs (default 5000) for its element to appear. The tab comes to the front if it is hidden, the mouse wheel scrolls the element into view, and the mouse moves there along a curved path, pauses, and clicks. The page sees trusted mouse events, unlike a click from browser_eval. To check the click worked, wait_for browser.element with a selector for the new state. input touch is refused, since a desktop has no touch screen.",
        "browser_swipe" to
            "Scroll a browser target with the mouse wheel, in notches. from and to are CSS viewport pixels, dragging content the way a finger would: from below to above scrolls down. Or pass a direction: up scrolls down the page, as a swipe up does. The mouse moves over the element or page first.",
        "browser_gesture" to
            "Drag the mouse along one pointer path on a browser target, in CSS viewport pixels: pressed at the first point and released at the last. More than one path is refused, since a mouse has one pointer.",
        "browser_key" to
            "Press a key on a browser target with keyDown and keyUp events, holding modifiers first, after bringing the tab to the front if it is hidden. Device keys such as HOME are refused.",
        "browser_type" to
            "Type text into a field in a browser target. ref or selector clicks the field first. Each character is a keyDown and keyUp, with Shift where needed, at a person's pace between minDelayMs and maxDelayMs. Characters no key produces, such as emoji, are inserted the way an input method commits them. clear selects all and deletes first; submit presses Enter.",
        "screenshot" to
            "PNG of the tab showing in the browser, captured over DevTools. Prefer browser_snapshot or browser_read, and use this for images, canvases, and layout. scale is 0.1 to 1, default 0.5.",
    )
