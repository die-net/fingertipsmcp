package net.die.phoneapi.helper.compat

import android.util.Log
import net.die.phoneapi.helper.StartResult

private const val TAG = "PhoneApiHelper"

/** The range `ActivityManager.isStartResultSuccessful` accepts. The SDK hides that method. */
private val START_SUCCESS_CODES = 0..99

/**
 * Runs [start] and returns the task [taskShowing] finds, or null. A code outside the success range
 * means Android started nothing, so a matching activity on top would be an older one that isn't
 * this call's to close. A wait that ran out says nothing about the start, so the lookup decides,
 * which closes an activity that came up in time.
 */
internal fun shellActivityTask(start: () -> StartResult, taskShowing: () -> Int?): Int? {
    val task =
        try {
            val result = start()
            if (!result.timedOut && result.code !in START_SUCCESS_CODES) {
                Log.w(TAG, "The shell activity did not start: ${result.code} ${result.refusal}")
                return null
            }
            taskShowing()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not open the shell activity", e)
            return null
        }
    if (task == null) Log.w(TAG, "The shell activity is not on top")
    return task
}
