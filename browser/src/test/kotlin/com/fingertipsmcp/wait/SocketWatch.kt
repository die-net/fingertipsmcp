package com.fingertipsmcp.wait

import com.fingertipsmcp.browser.DevtoolsSocket
import com.fingertipsmcp.browser.testEndpoints
import com.fingertipsmcp.core.BrowserService
import kotlinx.coroutines.CoroutineDispatcher

/** A watch whose pages and browser come from sockets [open] gives, as on Android. */
internal fun BrowserWatch(
    browser: BrowserService,
    io: CoroutineDispatcher,
    open: suspend (String, Long) -> DevtoolsSocket,
    now: () -> Long = ::monotonicMs,
    dialogFrom: suspend (String) -> Boolean = { false },
): BrowserWatch = BrowserWatch(browser, io, testEndpoints(io, open = open), now, dialogFrom)
