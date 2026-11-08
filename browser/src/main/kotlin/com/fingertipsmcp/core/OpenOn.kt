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
 * What [open] opens on its way to the result it closes itself, with [closeOnFailure].
 */
suspend fun <T : Closeable?> openOn(
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

/**
 * Runs [block] on this and returns its result, closing this first if [block] throws, a cancel
 * included. It is [use] for something the caller keeps once [block] returns, such as a socket that
 * a session takes over.
 */
inline fun <C : Closeable, R> C.closeOnFailure(block: (C) -> R): R {
    var returned = false
    try {
        return block(this).also { returned = true }
    } finally {
        if (!returned) closeQuietly(this)
    }
}

@PublishedApi
internal fun closeQuietly(opened: Closeable) {
    try {
        opened.close()
    } catch (_: IOException) {
        // The caller sees the failure that ended the open, not this one.
    }
}
