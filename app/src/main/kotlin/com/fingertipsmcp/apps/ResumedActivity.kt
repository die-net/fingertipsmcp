package com.fingertipsmcp.apps

/** `dumpsys activity activities`, whose resumed activity names the app in front. */
internal val RESUMED_ACTIVITY_ARGS = listOf("dumpsys", "activity", "activities")

/**
 * The package of the resumed activity in `dumpsys activity activities` output, or null. Android 11
 * prints `ResumedActivity: ActivityRecord{...}`, and later versions `topResumedActivity=...`.
 */
internal fun resumedPackage(dumpsys: String): String? = RESUMED.find(dumpsys)?.groupValues?.get(1)

private val RESUMED = Regex("""ResumedActivity[:=]\s*ActivityRecord\{\S+ \S+ ([^/\s}]+)/""")
