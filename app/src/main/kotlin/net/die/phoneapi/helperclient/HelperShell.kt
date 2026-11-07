package net.die.phoneapi.helperclient

import android.content.Intent
import android.os.RemoteException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.die.phoneapi.apps.ActivityStart
import net.die.phoneapi.helper.ShellResult as HelperShellResult
import net.die.phoneapi.model.ApiException

/** One finished helper command. Output is truncated by the helper, not here. */
data class ShellResult(val exit: Int, val stdout: String = "", val stderr: String = "") {
    val ok: Boolean
        get() = exit == 0
}

/** A one-line reason for a failure, preferring stderr, for error messages and logs. */
fun ShellResult.failureMessage(): String =
    listOf(stderr, stdout)
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.lineSequence()
        ?.first()
        ?.take(MAX_MESSAGE)
        .orEmpty()

/**
 * Runs commands as the shell UID through the helper. Every call needs the helper, so callers that
 * have a reduced-mode fallback should check [isAvailable] first instead of catching.
 */
class HelperShell(
    private val helper: HelperConnection,
    private val dispatcher: CoroutineDispatcher,
) {
    val isAvailable: Boolean
        get() = helper.isRunning

    /** Throws the `503 helper_unavailable` [exec] would, before any other side effects. */
    fun require() {
        helper.require()
    }

    /** The `503 helper_unavailable` for a call that needs the helper because of [why]. */
    fun unavailable(why: String): ApiException = helper.unavailable(why)

    /** Throws `503 helper_unavailable` when the helper isn't running, or `503 helper_error`. */
    suspend fun exec(
        argv: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    ): ShellResult {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        val proxy = helper.require()
        val delivered =
            withContext(dispatcher) {
                try {
                    proxy.exec(argv.toTypedArray(), timeoutMs, maxOutputBytes)
                } catch (e: RemoteException) {
                    val why = e.message?.takeIf { it.isNotBlank() } ?: "the helper stopped"
                    throw helperError("`${argv.first()}` did not run: $why", e)
                }
            }
        return delivered.toShellResult()
    }

    /**
     * Starts [intent] as the shell user, which may start activities from the background. With a
     * [waitMs], waits up to that long for it to be drawn, and the result names what took it. Throws
     * `503 helper_unavailable` when the helper isn't running, or `503 helper_error`, as when this
     * Android lacks the call.
     */
    internal suspend fun startActivity(intent: Intent, waitMs: Long = 0): ActivityStart {
        val proxy = helper.require()
        val started =
            withContext(dispatcher) {
                try {
                    proxy.startActivity(intent, waitMs)
                } catch (e: RemoteException) {
                    throw helperError("The start did not run: ${e.message.orEmpty()}", e)
                } catch (e: IllegalStateException) {
                    throw helperError("The start did not run: ${e.message.orEmpty()}", e)
                }
            }
        return ActivityStart(
            code = started.code,
            activity = started.activity,
            timedOut = started.timedOut,
            refusal = started.refusal,
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024
    }
}

private fun HelperShellResult.toShellResult(): ShellResult =
    ShellResult(exit = exitCode, stdout = stdout.orEmpty(), stderr = stderr.orEmpty())

private fun helperError(message: String, cause: Throwable? = null) =
    ApiException(503, "helper_error", message, cause = cause)

private const val MAX_MESSAGE = 200
