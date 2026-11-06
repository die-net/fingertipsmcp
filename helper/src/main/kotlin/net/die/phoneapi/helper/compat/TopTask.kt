package net.die.phoneapi.helper.compat

import android.app.ActivityManager
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method

/**
 * The package of the top task, through the activity task manager's Binder: what `dumpsys activity
 * activities` reports, without starting `dumpsys`, which takes about 100 ms on a phone. `getTasks`
 * gained trailing arguments over releases, so the shortest overload is used, with defaults that
 * keep its Android 10 meaning: no filtering, every display.
 */
internal class TopTask {
    private val manager: Any
    private val getTasks: Method

    init {
        val binder =
            serviceBinder("activity_task")
                ?: error("The activity task service is not available to this process")
        manager =
            reflective("IActivityTaskManager") {
                val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
                val asInterface = stub.getMethod("asInterface", IBinder::class.java)
                checkNotNull(asInterface.invoke(null, binder)) { "IActivityTaskManager is null" }
            }
        getTasks =
            manager.javaClass.methods
                .filter { method ->
                    method.name == "getTasks" &&
                        method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
                }
                .minByOrNull { it.parameterTypes.size }
                ?: error("IActivityTaskManager has no getTasks method")
        Log.i(TAG, "getTasks ${getTasks.parameterTypes.joinToString { it.simpleName }}")
    }

    /** The top task's package, or null when there is none. */
    fun packageName(): String? {
        val params = getTasks.parameterTypes
        val args = arrayOfNulls<Any>(params.size)
        args[0] = 1
        for (i in 1 until params.size) {
            args[i] =
                when (params[i]) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> INVALID_DISPLAY
                    else -> null
                }
        }
        val tasks =
            reflective("getTasks") {
                @Suppress("SpreadOperator") // A few trailing defaults; the copy is the call itself.
                getTasks.invoke(manager, *args) as? List<*>
            } ?: return null
        val task = tasks.firstOrNull() as? ActivityManager.RunningTaskInfo ?: return null
        return task.topActivity?.packageName
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        /** Display.INVALID_DISPLAY, which asks for tasks on every display. */
        const val INVALID_DISPLAY = -1
    }
}
