package com.fingertipsmcp.tabs

import com.fingertipsmcp.browser.waitOnStoppedTab
import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.core.WaitService
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.WaitCondition
import com.fingertipsmcp.model.WaitRequest
import com.fingertipsmcp.model.WaitResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits on the tabs a caller may use. A `browser.*` condition's tab must be one [check] allows.
 * With [browser], one that names no tab gets the only page the caller can see, rather than
 * whichever page is open; without it, the wait service picks as before.
 */
internal class SessionWaits(
    private val base: WaitService,
    private val browser: BrowserService?,
    private val check: suspend (String) -> Unit,
) : WaitService {
    override suspend fun wait(request: WaitRequest, scopes: Set<Scope>): WaitResult {
        val all = request.all.map { scoped(it) }
        val any = request.any.map { scoped(it) }
        val scopedRequest = request.copy(all = all, any = any)
        val tabs = (all + any).mapNotNull { targeted(it)?.target }.distinct()
        val watching = browser
        if (watching == null || tabs.isEmpty()) return base.wait(scopedRequest, scopes)
        // A tab whose timers stopped will not change, so the wait fails as soon as a check shows
        // it, rather than running out its timeout. Input starts checks; so does the wait.
        tabs.forEach { watching.watchTimers(it) }
        return coroutineScope {
            val waiting = async { base.wait(scopedRequest, scopes) }
            while (waiting.isActive) {
                val stopped = tabs.firstOrNull { watching.timersStalled(it) }
                if (stopped != null) {
                    waiting.cancel()
                    throw waitOnStoppedTab(stopped)
                }
                withTimeoutOrNull(STALL_POLL_MS) { waiting.join() }
            }
            waiting.await()
        }
    }

    private suspend fun scoped(condition: WaitCondition): WaitCondition {
        val slot = targeted(condition) ?: return condition
        val target = slot.target
        if (target != null) {
            check(target)
            return condition
        }
        if (browser == null) return condition
        val pages = browser.targets().filter { it.type == "page" }
        val only =
            pages.singleOrNull()
                ?: throw ApiException.badRequest(
                    "Pass target: you can see ${pages.size} browser tabs, not one"
                )
        return slot.with(only.id)
    }
}

/** A condition's tab, and the condition with another tab in its place. */
private data class Targeted(val target: String?, val with: (String) -> WaitCondition)

/** The tab a condition is on. Null for conditions on no tab, such as browser.newTarget. */
private fun targeted(condition: WaitCondition): Targeted? =
    when (condition) {
        is WaitCondition.BrowserUrl -> Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserLifecycle ->
            Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserNetworkIdle ->
            Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserElement ->
            Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserRequest ->
            Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserDialog -> Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserLog -> Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserLoaded -> Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserSettled ->
            Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserNewTarget,
        is WaitCondition.Node,
        is WaitCondition.Window,
        is WaitCondition.Ime,
        is WaitCondition.Idle,
        is WaitCondition.Screen,
        is WaitCondition.Keyguard -> null
    }

private const val STALL_POLL_MS = 250L

/** [condition] with [target] as its tab, when it is a browser condition that names none. */
internal fun withTarget(condition: WaitCondition, target: String?): WaitCondition {
    if (target == null) return condition
    val slot = targeted(condition) ?: return condition
    return if (slot.target == null) slot.with(target) else condition
}
