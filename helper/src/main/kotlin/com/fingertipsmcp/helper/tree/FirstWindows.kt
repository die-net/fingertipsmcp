package com.fingertipsmcp.helper.tree

/**
 * The windows [read] reports, waiting up to [settleMs] after UiAutomation connected at
 * [connectedAtMs] for the first ones. Right after it connects, Android can report no windows until
 * it has sent the new connection its window state, and a snapshot then came back empty with no
 * error. Later, or once [settleMs] has passed, an empty list stands.
 */
internal fun <T> settledWindows(
    connectedAtMs: Long,
    now: () -> Long,
    sleep: (Long) -> Unit,
    settleMs: Long = WINDOWS_SETTLE_MS,
    read: () -> List<T>,
): List<T> {
    var windows = read()
    while (windows.isEmpty() && now() - connectedAtMs < settleMs) {
        sleep(WINDOWS_POLL_MS)
        windows = read()
    }
    return windows
}

/** How long after connecting an empty window list is taken as not there yet. */
internal const val WINDOWS_SETTLE_MS = 1_000L
private const val WINDOWS_POLL_MS = 20L
