package net.die.phoneapi.lease

import kotlin.coroutines.cancellation.CancellationException
import net.die.phoneapi.core.ClipboardService
import net.die.phoneapi.core.InputService
import net.die.phoneapi.core.PowerService
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.KeyRequest

/**
 * Leaves the screen in a known state between holders: keyboard hidden, Chrome on its blank
 * placeholder tab, home, and no clip PhoneAPI wrote or copied left on the clipboard. It locks the
 * phone when the lease says to, which is when no one waits and sessions found it locked. Returns
 * the steps that failed.
 *
 * [ready] runs when a session gets the screen, before its first screen call. It empties the
 * clipboard, since a person may have copied a password or other text since cleanup ran.
 */
class ScreenCleanup(
    private val input: InputService,
    private val power: PowerService,
    private val clipboard: ClipboardService,
    private val showPlaceholder: suspend () -> Unit = {},
    /** Waits for HOME to reach the screen. False when it did not, so home is not showing. */
    private val awaitHome: suspend () -> Boolean = { true },
) {
    suspend fun run(lock: Boolean): List<String> = buildList {
        step("hide the keyboard") { input.hideIme() }
        step("show the blank tab") {
            showPlaceholder()
            ActionResult(ok = true)
        }
        step("go home") { goHome() }
        if (lock) step("lock") { power.lock() }
        step("clear the clipboard") { clipboard.clearOwn() }
    }

    /**
     * Presses HOME, which returns before the launcher comes forward. That is the end of the step,
     * since the lease takes the end of cleanup as the end of what it did to the screen, and a
     * session that opens a tab after that must not be blamed for the launcher arriving.
     */
    private suspend fun goHome(): ActionResult {
        val pressed = input.key(KeyRequest(key = "HOME"))
        if (!pressed.ok || awaitHome()) return pressed
        return ActionResult(ok = false, message = "the home screen did not come forward")
    }

    suspend fun ready(): List<String> = buildList {
        step("clear the clipboard") { clipboard.clear() }
    }

    private suspend fun MutableList<String>.step(what: String, block: suspend () -> ActionResult) {
        try {
            val result = block()
            if (!result.ok) add("$what: ${result.message ?: "it did not work"}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            add("$what: ${e.message ?: e.error}")
        }
    }
}
