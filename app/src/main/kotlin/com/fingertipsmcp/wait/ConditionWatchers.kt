package com.fingertipsmcp.wait

import android.os.SystemClock
import com.fingertipsmcp.core.DeviceStateTracker
import com.fingertipsmcp.core.STATE_POLL_MS
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.DeviceStateSummary
import com.fingertipsmcp.model.FindRequest
import com.fingertipsmcp.model.PAGE_NODES_OFF
import com.fingertipsmcp.model.ScreenState
import com.fingertipsmcp.model.WaitCondition
import com.fingertipsmcp.tree.TreeSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Whether a condition holds right now, and when to look again if nothing else happens. */
internal data class Check(val satisfied: Boolean, val recheckAtMs: Long? = null)

internal fun interface ConditionWatcher {
    suspend fun check(): Check
}

/**
 * Turns [WaitCondition]s into watchers. Selectors and state names are validated while the watcher
 * is built, so a bad request fails before the engine starts waiting.
 */
internal class ConditionWatchers(
    private val tree: TreeSession,
    private val state: DeviceStateTracker,
    private val io: CoroutineDispatcher,
    private val lastChangeMs: () -> Long,
    /** Whether a person holds the screen in the viewer, for `screen` with `person`. */
    private val personHolds: () -> Boolean,
    private val browser: BrowserWatch? = null,
) {
    fun watcher(condition: WaitCondition): ConditionWatcher =
        when (condition) {
            is WaitCondition.Node -> node(condition)
            is WaitCondition.Window -> window(condition)
            is WaitCondition.Ime -> deviceState { it.ime.visible == condition.visible }
            is WaitCondition.Idle -> idle(condition.quietMs)
            is WaitCondition.Screen -> screen(condition)
            is WaitCondition.Keyguard -> polledState { it.keyguard.locked == condition.locked }
            is WaitCondition.BrowserUrl,
            is WaitCondition.BrowserLifecycle,
            is WaitCondition.BrowserNetworkIdle,
            is WaitCondition.BrowserElement,
            is WaitCondition.BrowserRequest,
            is WaitCondition.BrowserDialog,
            is WaitCondition.BrowserNewTarget,
            is WaitCondition.BrowserLog,
            is WaitCondition.BrowserLoaded,
            is WaitCondition.BrowserSettled -> browser(condition)
        }

    private fun node(condition: WaitCondition.Node): ConditionWatcher {
        val test = NodeConditions.test(condition.state)
        val request =
            FindRequest(
                selector = condition.selector,
                limit = 1,
                includeInvisible = NodeConditions.includeInvisible(condition.state),
            )
        return ConditionWatcher {
            try {
                Check(test(tree.find(request).matches.firstOrNull()))
            } catch (e: ApiException) {
                if (e.error != "stale_ref" && e.error != PAGE_NODES_OFF) throw e
                // A read just after the page changed can match the old page from the cache, and a
                // page with its nodes off shows none. Neither says anything about the node, which
                // would make `absent` match, so look again.
                pollAgain()
            }
        }
    }

    private fun window(condition: WaitCondition.Window): ConditionWatcher {
        val packageName = condition.packageName
        val title = condition.titleContains
        if (packageName == null && title == null) {
            throw ApiException.badRequest("A window condition needs a package or titleContains")
        }
        return ConditionWatcher {
            val packageOk = packageName == null || packageName == state.current.foregroundPackage
            Check(packageOk && (title == null || hasWindowTitled(title)))
        }
    }

    /** Window titles come straight off the window list, with no tree traversal. */
    private suspend fun hasWindowTitled(text: String): Boolean =
        withContext(io) { tree.windowTitle(text) }

    /** `on` polls, as [polledState] does. The lease reports who holds the screen as it changes. */
    private fun screen(condition: WaitCondition.Screen): ConditionWatcher {
        val on = condition.on
        val person = condition.person
        if (on == null && person == null) {
            throw ApiException.badRequest("A screen condition needs on or person")
        }
        return ConditionWatcher {
            val personOk = person == null || personHolds() == person
            val held = personOk && (on == null || (state.refresh().screen == ScreenState.ON) == on)
            if (held || on == null) Check(held) else pollAgain()
        }
    }

    /** Satisfied once the UI has been quiet for [quietMs]; nothing to react to, so it's timed. */
    private fun idle(quietMs: Long): ConditionWatcher {
        if (quietMs < 0) throw ApiException.badRequest("quietMs must not be negative")
        return ConditionWatcher {
            val quietAt = lastChangeMs() + quietMs
            val now = SystemClock.uptimeMillis()
            if (now >= quietAt) Check(satisfied = true) else Check(false, recheckAtMs = quietAt)
        }
    }

    /** IME visibility is pushed onto the tracker, so the cached summary is current. */
    private fun deviceState(predicate: (DeviceStateSummary) -> Boolean) = ConditionWatcher {
        Check(predicate(state.current))
    }

    /**
     * Screen and keyguard are not broadcast for every change, so the check re-reads them and, until
     * it holds, asks to be checked again in [STATE_POLL_MS] even if nothing happens.
     */
    private fun polledState(predicate: (DeviceStateSummary) -> Boolean) = ConditionWatcher {
        if (predicate(state.refresh())) Check(satisfied = true) else pollAgain()
    }

    private fun pollAgain() =
        Check(satisfied = false, recheckAtMs = SystemClock.uptimeMillis() + STATE_POLL_MS)

    private fun browser(condition: WaitCondition): ConditionWatcher {
        validateBrowser(condition)
        val watch = browser ?: throw browserUnavailable()
        return ConditionWatcher { watch.check(condition) }
    }

    private fun browserUnavailable() =
        ApiException(500, "internal", "Browser wait has no DevTools session")
}
