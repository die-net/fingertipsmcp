package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.DevtoolsSocketInfo
import kotlinx.coroutines.delay

/**
 * Whether [sockets] lacks Chrome's, so Chrome is not running. False when they cannot be listed,
 * since opening the tab then reports why.
 */
internal suspend fun noChromeSocket(sockets: suspend () -> List<DevtoolsSocketInfo>): Boolean =
    try {
        sockets().none { it.name == CHROME_SOCKET }
    } catch (_: ApiException) {
        false
    }

/**
 * Chrome's DevTools socket from [sockets], starting Chrome with [start] first when it is not
 * running, as tapping its icon would. Chrome opens the socket a moment after it starts. [gap] says
 * why there is still none after [CHROME_START_POLLS] polls.
 */
internal suspend fun startedChrome(
    sockets: suspend () -> List<DevtoolsSocketInfo>,
    start: suspend () -> Unit,
    gap: () -> String,
): DevtoolsSocketInfo {
    suspend fun chrome() = sockets().firstOrNull { it.name == CHROME_SOCKET }
    chrome()?.let {
        return it
    }
    start()
    repeat(CHROME_START_POLLS) {
        delay(CHROME_START_POLL_MS)
        chrome()?.let { socket ->
            return socket
        }
    }
    throw ApiException.unavailable(
        "browser_unavailable",
        "Chrome did not open its DevTools socket within " +
            "${CHROME_START_POLLS * CHROME_START_POLL_MS / MS_PER_S} s of starting. ${gap()}",
    )
}

private const val CHROME_START_POLLS = 50
private const val CHROME_START_POLL_MS = 200L
private const val MS_PER_S = 1_000L
