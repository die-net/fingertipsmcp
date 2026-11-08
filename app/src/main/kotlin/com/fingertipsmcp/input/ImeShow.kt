package com.fingertipsmcp.input

import com.fingertipsmcp.core.DeviceStateTracker
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.FindRequest
import com.fingertipsmcp.model.ImeShowRequest
import com.fingertipsmcp.model.NodeSelector
import com.fingertipsmcp.model.UiNode
import com.fingertipsmcp.tree.TreeSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Taps an editable field so the IME appears, as a person would, through the same injected touch as
 * `tap`. Callers run this inside the input action wrapper, off the main thread.
 */
internal class ImeShow(
    private val tree: TreeSession,
    private val touch: TouchInput,
    private val state: DeviceStateTracker,
) {
    suspend fun show(request: ImeShowRequest): ActionResult {
        if (request.selector == null && state.ime.visible) {
            return ActionResult(ok = true, message = "The keyboard is already visible")
        }
        val node =
            request.selector?.let(::find)
                ?: focusedField()
                ?: throw ApiException.notFound(
                    "No field has input focus. Pass a selector for the field to type into."
                )
        return tapUp(node)
    }

    /** Taps the focused field to bring the keyboard up, or gives null when no field has focus. */
    suspend fun showForFocused(): ActionResult? = focusedField()?.let { tapUp(it) }

    private suspend fun tapUp(node: UiNode): ActionResult {
        val outcome = touch.tap(tree.touchTarget(node.ref, force = false))
        val shown =
            state.ime.visible ||
                withTimeoutOrNull(SHOW_WAIT_MS) { state.state.first { it.ime.visible } } != null
        return ActionResult(
            ok = shown,
            backend = outcome.backend,
            points = outcome.points,
            message = if (shown) null else "The keyboard did not appear",
        )
    }

    private fun find(selector: NodeSelector): UiNode =
        tree.find(FindRequest(selector, limit = 1)).matches.firstOrNull()
            ?: throw ApiException.notFound("No node matches the selector")

    /** The editable field that has focus, which a tap brings the keyboard up for. */
    private fun focusedField(): UiNode? =
        tree
            .find(FindRequest(NodeSelector(editable = true, focused = true), limit = 1))
            .matches
            .firstOrNull()

    private companion object {
        const val SHOW_WAIT_MS = 2_000L
    }
}
