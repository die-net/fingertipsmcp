package com.fingertipsmcp.apps

import android.content.ComponentName
import android.content.Intent
import androidx.core.net.toUri
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.IntentRequest

/** The intent [request] asks for, in a new task, which every activity started here needs. */
internal fun startIntent(request: IntentRequest): Intent {
    val intent = Intent(request.action)
    request.data?.let { intent.setData(it.toUri()) }
    request.packageName?.let(intent::setPackage)
    request.component?.let { intent.setComponent(ComponentName.unflattenFromString(it)) }
    request.categories.forEach(intent::addCategory)
    request.extras.forEach { (key, value) -> intent.putExtra(key, value) }
    request.flags?.let(intent::setFlags)
    return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Why an activity did not start. */
internal enum class StartFailure {
    UNRESOLVED,
    DENIED,
    OTHER,
}

/**
 * What the activity manager said of a start. [code] is an `ActivityManager.START_*` code, and
 * [activity] the component that took the intent, when the start waited to hear it. [timedOut] means
 * the wait ran out, so [code] says nothing. [refusal] is Android's reason for refusing by
 * exception.
 */
internal data class ActivityStart(
    val code: Int,
    val activity: String? = null,
    val timedOut: Boolean = false,
    val refusal: String? = null,
) {
    val activityPackage: String?
        get() = activity?.substringBefore('/')

    /**
     * Several apps matched, so Android is asking the user which one to use. No code says so, so the
     * name of the activity that took the intent is the only signal, as docs/device-notes.md
     * records.
     */
    val chooser: Boolean
        get() = activity?.let { CHOOSER.containsMatchIn(it) } == true

    val failure: StartFailure?
        get() =
            when {
                timedOut -> StartFailure.OTHER
                code == START_INTENT_NOT_RESOLVED || code == START_CLASS_NOT_FOUND ->
                    StartFailure.UNRESOLVED
                code == START_PERMISSION_DENIED -> StartFailure.DENIED
                code == START_SUCCESS || code in WARNINGS -> null
                else -> StartFailure.OTHER
            }

    /** Why the activity is not new though the start worked, or null. */
    val warning: String?
        get() = if (timedOut) null else WARNINGS[code]

    /** Why the start failed, for an error message. */
    val message: String
        get() =
            when {
                timedOut -> "Android did not finish starting it in time"
                refusal != null -> refusal
                code == START_INTENT_NOT_RESOLVED -> "no activity matches it"
                code == START_CLASS_NOT_FOUND -> "the activity class does not exist"
                code == START_PERMISSION_DENIED -> "permission denied"
                else -> "start result $code"
            }
}

// ActivityManager's START_* codes, which the SDK hides. docs/device-notes.md says where they were
// read.
private const val START_SUCCESS = 0
private const val START_RETURN_INTENT_TO_CALLER = 1
private const val START_TASK_TO_FRONT = 2
private const val START_DELIVERED_TO_TOP = 3
private const val START_SWITCHES_CANCELED = 100
private const val START_INTENT_NOT_RESOLVED = -91
private const val START_CLASS_NOT_FOUND = -92
private const val START_PERMISSION_DENIED = -94

/** The codes of a start that worked without starting a new activity, and what each means. */
private val WARNINGS =
    mapOf(
        START_RETURN_INTENT_TO_CALLER to
            "Activity not started because intent should be handled by the caller",
        START_TASK_TO_FRONT to
            "Activity not started, its current task has been brought to the front",
        START_DELIVERED_TO_TOP to
            "Activity not started, intent has been delivered to currently running top-most " +
                "instance.",
        START_SWITCHES_CANCELED to
            "Activity not started because the current activity is being kept for the user.",
    )

private val CHOOSER = Regex("""/[\w.$]*(ResolverActivity|ChooserActivity)$""")

/** The error for [what], an intent's name, that could not start, by why it failed. */
internal fun intentFailed(what: String, failure: StartFailure, message: String): ApiException =
    when (failure) {
        StartFailure.UNRESOLVED -> ApiException.notFound("Nothing handles $what: $message")
        StartFailure.DENIED -> intentRefused("Android refused $what: $message")
        StartFailure.OTHER ->
            ApiException(409, "intent_failed", "Android could not start $what: $message")
    }

/**
 * Android would not start the intent, for example an activity that is not exported. It is not a
 * missing scope, so it is not 403 `forbidden`.
 */
internal fun intentRefused(message: String, cause: Throwable? = null): ApiException =
    ApiException(409, "intent_refused", message, cause = cause)

/** How an error message names the intent [request] starts. */
internal fun describe(request: IntentRequest): String =
    request.component ?: request.packageName ?: request.data ?: request.action
