package net.die.phoneapi.apps

import net.die.phoneapi.helperclient.ShellResult
import net.die.phoneapi.helperclient.failureMessage
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.IntentRequest

/**
 * `am start -W` for [request]. Android 10 and 11 parse the same options. `am` adds
 * FLAG_ACTIVITY_NEW_TASK itself.
 */
internal fun amStartArgs(request: IntentRequest): List<String> = buildList {
    addAll(listOf("am", "start", "-W", "-a", request.action))
    request.data?.let { addAll(listOf("-d", it)) }
    request.packageName?.let { addAll(listOf("-p", it)) }
    request.component?.let { addAll(listOf("-n", it)) }
    request.categories.forEach { addAll(listOf("-c", it)) }
    request.extras.forEach { (key, value) -> addAll(listOf("--es", key, value)) }
    request.flags?.let { addAll(listOf("-f", it.toString())) }
}

internal enum class AmFailure {
    UNRESOLVED,
    DENIED,
    OTHER,
}

/** What `am start -W` reported. [activity] is the component that took the intent. */
internal data class AmStart(
    val activity: String? = null,
    val warning: String? = null,
    val failure: AmFailure? = null,
    val message: String? = null,
) {
    val activityPackage: String?
        get() = activity?.substringBefore('/')

    /** Several apps matched, so Android is asking the user which one to use. */
    val chooser: Boolean
        get() = activity?.let { CHOOSER.containsMatchIn(it) } == true
}

/** `am start` exits 0 when the activity manager refuses an intent, so errors come from the text. */
internal fun parseAmStart(result: ShellResult): AmStart {
    val lines =
        (result.stdout.lineSequence() + result.stderr.lineSequence())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    val error = lines.firstOrNull { it.startsWith(ERROR) }?.removePrefix(ERROR)?.trim()
    val exception = lines.firstOrNull { EXCEPTION.containsMatchIn(it) }
    if (error != null || exception != null || !result.ok) {
        val message =
            error
                ?: exception
                ?: result.stderr.takeIf { it.isNotBlank() }?.let { result.failureMessage() }
                ?: "`am start` exited ${result.exit}"
        return AmStart(failure = classify(message), message = message)
    }
    return AmStart(
        activity = lines.firstOrNull { it.startsWith(ACTIVITY) }?.removePrefix(ACTIVITY)?.trim(),
        warning = lines.firstOrNull { it.startsWith(WARNING) }?.removePrefix(WARNING)?.trim(),
    )
}

private fun classify(message: String): AmFailure =
    when {
        "unable to resolve" in message || "does not exist" in message -> AmFailure.UNRESOLVED
        "SecurityException" in message || "permission" in message.lowercase() -> AmFailure.DENIED
        else -> AmFailure.OTHER
    }

private const val ERROR = "Error:"
private const val WARNING = "Warning:"
private const val ACTIVITY = "Activity:"
private val EXCEPTION = Regex("""^[\w.$]+(Exception|Error): """)
private val CHOOSER = Regex("""/[\w.$]*(ResolverActivity|ChooserActivity)$""")

/** The error for an intent `am start` could not start, by why it failed. */
internal fun intentFailed(
    request: IntentRequest,
    failure: AmFailure,
    message: String,
): ApiException =
    when (failure) {
        AmFailure.UNRESOLVED ->
            ApiException.notFound("Nothing handles ${describe(request)}: $message")
        AmFailure.DENIED -> intentRefused("Android refused ${describe(request)}: $message")
        AmFailure.OTHER ->
            ApiException(409, "intent_failed", "am start failed for ${describe(request)}: $message")
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
