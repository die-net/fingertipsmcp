package com.fingertipsmcp.helper.compat

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import com.fingertipsmcp.helper.StartResult
import java.lang.reflect.Method
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Starts activities through the activity task manager's Binder, as the shell user, which may start
 * them from the background. `am start` makes the same call, and then prints its result code as text
 * and exits 0, so calling it here keeps the code. Android 11 added a calling feature id after the
 * calling package, so the arguments are placed by their types.
 */
internal class ActivityStarter {
    private val manager = activityTaskManager()
    private val start = method("startActivity")
    private val startAndWait = method("startActivityAndWait")

    init {
        Log.i(
            TAG,
            "startActivityAndWait ${startAndWait.parameterTypes.joinToString { it.simpleName }}",
        )
    }

    /**
     * Starts [intent]. With a [timeoutMs] above 0, waits up to that long for the activity to be
     * drawn, as `am start -W` does, which also names the component that took it. Android can wait
     * far longer, as for a phone that is locked, so the wait is on a thread of its own.
     */
    fun start(intent: Intent, timeoutMs: Long): StartResult {
        if (timeoutMs <= 0) return started(intent)
        val call = FutureTask { waited(intent) }
        Thread(call, "start-activity").apply { isDaemon = true }.start()
        return try {
            call.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            StartResult().apply { timedOut = true }
        } catch (e: ExecutionException) {
            throw e.cause as? RuntimeException ?: IllegalStateException("Start failed: $e", e)
        }
    }

    private fun started(intent: Intent): StartResult = refused {
        val code =
            reflective("startActivity") {
                checkNotNull(invoke(start, arguments(start, intent))) { "No result from the start" }
            }
        StartResult().apply { this.code = code as Int }
    }

    private fun waited(intent: Intent): StartResult = refused {
        val args = arguments(startAndWait, intent)
        // The user is the last argument, after the flags that startActivity ends with.
        args[args.lastIndex] = USER_CURRENT
        reflective("startActivityAndWait") {
            val wait = checkNotNull(invoke(startAndWait, args)) { "No result from the start" }
            val type = wait.javaClass
            StartResult().apply {
                code = type.getField("result").getInt(wait)
                activity =
                    (type.getField("who").get(wait) as? ComponentName)?.flattenToShortString()
            }
        }
    }

    /** Runs [block], turning Android's refusal by exception into a result. */
    private inline fun refused(block: () -> StartResult): StartResult =
        try {
            block()
        } catch (e: SecurityException) {
            StartResult().apply {
                code = START_PERMISSION_DENIED
                refusal = e.message
            }
        }

    private fun invoke(method: Method, args: Array<Any?>): Any? {
        @Suppress("SpreadOperator") // A dozen arguments; the copy is the call itself.
        return method.invoke(manager, *args)
    }

    /** The arguments of [method] for [intent] from the shell, every other one 0 or null. */
    private fun arguments(method: Method, intent: Intent): Array<Any?> {
        val params = method.parameterTypes
        val args = arrayOfNulls<Any>(params.size)
        params.forEachIndexed { i, type -> if (type == Int::class.javaPrimitiveType) args[i] = 0 }
        args[params.indexOf(String::class.java)] = SHELL_PACKAGE
        args[params.indexOf(Intent::class.java)] = intent
        return args
    }

    private fun method(name: String): Method =
        manager.javaClass.methods.firstOrNull { it.name == name }
            ?: error("IActivityTaskManager has no $name method")

    private companion object {
        const val TAG = "FingertipsHelper"
        const val SHELL_PACKAGE = "com.android.shell"
        /** UserHandle.USER_CURRENT, which `am` starts activities as. */
        const val USER_CURRENT = -2
        /** ActivityManager.START_PERMISSION_DENIED. */
        const val START_PERMISSION_DENIED = -94
    }
}
