package net.die.phoneapi.helper.compat

import android.app.ActivityManager
import android.content.ComponentName
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method

/** The activity task manager, through its Binder. */
internal fun activityTaskManager(): Any {
    val binder =
        serviceBinder("activity_task")
            ?: error("The activity task service is not available to this process")
    return reflective("IActivityTaskManager") {
        val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        checkNotNull(asInterface.invoke(null, binder)) { "IActivityTaskManager is null" }
    }
}

/**
 * The package of the top task, through the activity task manager's Binder: what `dumpsys activity
 * activities` reports, without starting `dumpsys`, which takes about 100 ms on a phone. `getTasks`
 * gained trailing arguments over releases, so the shortest overload is used, with defaults that
 * keep its Android 10 meaning: no filtering, every display.
 */
internal class TopTask {
    private val manager = activityTaskManager()
    private val getTasks: Method

    init {
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
    fun packageName(): String? = top()?.topActivity?.packageName

    /**
     * The id of the top task if [component] is its top activity, or null. A caller that started
     * that activity uses it to close only the task it opened.
     */
    fun taskShowing(component: ComponentName): Int? =
        top()?.takeIf { it.topActivity == component }?.taskId

    /** Removes the task [taskId] and finishes its activities. False when there is no such task. */
    fun removeTask(taskId: Int): Boolean =
        reflective("removeTask") {
            manager.javaClass
                .getMethod("removeTask", Int::class.javaPrimitiveType)
                .invoke(manager, taskId) as Boolean
        }

    private fun top(): ActivityManager.RunningTaskInfo? {
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
        return tasks.firstOrNull() as? ActivityManager.RunningTaskInfo
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        /** Display.INVALID_DISPLAY, which asks for tasks on every display. */
        const val INVALID_DISPLAY = -1
    }
}
