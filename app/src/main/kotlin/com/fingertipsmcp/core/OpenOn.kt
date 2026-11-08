package com.fingertipsmcp.core

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * Runs [open] on [context] and returns what it opened, which the caller then owns.
 *
 * A blocking open, such as a binder call or a socket connect, finishes even when the caller is
 * cancelled meanwhile. [withContext] then throws instead of returning its result, so that result
 * would be dropped with its file descriptor still open. This closes it before the failure goes on.
 */
internal suspend fun <T : Closeable?> openOn(
    context: CoroutineContext,
    open: suspend CoroutineScope.() -> T,
): T {
    val opened = AtomicReference<T>()
    var returned = false
    try {
        return withContext(context) { open().also(opened::set) }.also { returned = true }
    } finally {
        if (!returned) opened.get()?.let(::closeQuietly)
    }
}

private fun closeQuietly(opened: Closeable) {
    try {
        opened.close()
    } catch (_: IOException) {
        // The caller sees the failure that ended the open, not this one.
    }
}
