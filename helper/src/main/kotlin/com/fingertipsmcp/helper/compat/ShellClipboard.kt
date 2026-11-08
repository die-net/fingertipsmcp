package com.fingertipsmcp.helper.compat

import android.content.ClipData
import android.content.ClipDescription
import android.content.IOnPrimaryClipChangedListener
import android.os.IBinder
import android.os.Process
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The clipboard service's Binder, called as the shell user, which may read the clip while another
 * app is in front. getPrimaryClip, getPrimaryClipDescription, setPrimaryClip, clearPrimaryClip, and
 * addPrimaryClipChangedListener gained an attribution tag on Android 12 and a device id on Android
 * 14, so each call fills its String arguments with the package then no tag, and its int arguments
 * with the user then the default device.
 */
internal class ShellClipboard {
    private val service: Any
    private val get: Method
    private val set: Method
    // Looked up on first use, so a release that lacks it can still read and write the clip.
    private val clear: Method by lazy { method("clearPrimaryClip") }
    private val describe: Method by lazy { method("getPrimaryClipDescription") }
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var changes = 0L

    private val listener =
        object : IOnPrimaryClipChangedListener.Stub() {
            override fun dispatchPrimaryClipChanged() {
                lock.withLock {
                    changes++
                    changed.signalAll()
                }
            }
        }

    init {
        val binder =
            serviceBinder("clipboard")
                ?: error("The clipboard service is not available to this process")
        service =
            reflective("IClipboard") {
                val stub = Class.forName("android.content.IClipboard\$Stub")
                val asInterface = stub.getMethod("asInterface", IBinder::class.java)
                checkNotNull(asInterface.invoke(null, binder)) { "IClipboard is null" }
            }
        get = method("getPrimaryClip")
        set = method("setPrimaryClip")
        try {
            call(method("addPrimaryClipChangedListener"), listener)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not watch the clipboard", e)
        }
    }

    /** The primary clip, or null when the clipboard is empty. */
    fun primaryClip(): ClipData? = call(get) as? ClipData

    fun setPrimaryClip(clip: ClipData) {
        call(set, clip)
    }

    fun clearPrimaryClip() {
        call(clear)
    }

    /** When the clip was put on the clipboard, or -1 when it is empty. */
    fun primaryClipTimestamp(): Long = (call(describe) as? ClipDescription)?.timestamp ?: -1

    /**
     * Waits up to [timeoutMs] for the change count to pass [seen], and returns it. The count starts
     * at 0 when this object is made, so -1 returns it at once.
     */
    fun awaitChange(seen: Long, timeoutMs: Long): Long = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceIn(0L, MAX_WAIT_MS))
        while (changes <= seen && left > 0) left = changed.awaitNanos(left)
        changes
    }

    private fun method(name: String): Method =
        service.javaClass.methods
            .firstOrNull { it.name == name }
            ?.also { Log.i(TAG, "$name ${it.parameterTypes.joinToString { p -> p.simpleName }}") }
            ?: error("IClipboard has no $name method")

    /** Calls [method] with [first], if given, then the package, tag, user, and device by type. */
    private fun call(method: Method, first: Any? = null): Any? {
        val params = method.parameterTypes
        val args = arrayOfNulls<Any>(params.size)
        val strings = ArrayDeque(listOf(PACKAGE, null))
        val ints = ArrayDeque(listOf(USER, DEVICE_ID_DEFAULT))
        for (i in params.indices) {
            args[i] =
                when {
                    i == 0 && first != null -> first
                    params[i] == String::class.java -> strings.removeFirstOrNull()
                    params[i] == Int::class.javaPrimitiveType -> ints.removeFirstOrNull() ?: 0
                    else -> null
                }
        }
        return reflective(method.name) {
            @Suppress("SpreadOperator") // A few arguments; the copy is the call itself.
            method.invoke(service, *args)
        }
    }

    private companion object {
        const val TAG = "FingertipsHelper"
        /** Whose clipboard: the package must match this process's uid. */
        val PACKAGE = if (Process.myUid() == Process.ROOT_UID) "root" else "com.android.shell"
        /** UserHandle.PER_USER_RANGE. */
        val USER = Process.myUid() / 100_000
        /** Context.DEVICE_ID_DEFAULT, the phone itself rather than a virtual device. */
        const val DEVICE_ID_DEFAULT = 0
        /** Keeps a Binder thread from waiting long. */
        const val MAX_WAIT_MS = 5_000L
    }
}
