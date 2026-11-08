package com.fingertipsmcp.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Polls [done] until it holds or [timeoutMs] is up, and says whether it held. This is for a result
 * nothing announces, such as a tab switch or a keyboard redrawing, which takes as long as the phone
 * takes.
 */
suspend fun settled(timeoutMs: Long, done: suspend () -> Boolean): Boolean =
    withTimeoutOrNull(timeoutMs) {
        while (!done()) delay(SETTLE_POLL_MS)
        true
    } == true

/**
 * Returns once [read] gives the same answer twice in a row, [gapMs] apart, or at [timeoutMs]. Past
 * that the thing keeps moving by itself, as a page that animates its size can, so waiting longer
 * would not help.
 */
suspend fun <T> holdsStill(timeoutMs: Long, gapMs: Long, read: suspend () -> T) {
    withTimeoutOrNull(timeoutMs) {
        var last = read()
        while (true) {
            delay(gapMs)
            val now = read()
            if (now == last) break
            last = now
        }
    }
}

private const val SETTLE_POLL_MS = 50L
