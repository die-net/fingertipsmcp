package net.die.phoneapi.input

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.core.DeviceStateTracker
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.NodeSelector
import net.die.phoneapi.model.UiNode
import net.die.phoneapi.tree.TreeSession

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
        val outcome = touch.tap(tree.touchTarget(node.ref, force = false))
        if (!outcome.ok) {
            throw ApiException(409, "gesture_cancelled", "The system cancelled the touch")
        }
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
            .find(FindRequest(NodeSelector(editable = true), limit = MAX_FIELDS))
            .matches
            .firstOrNull { "focused" in it.states }

    private companion object {
        const val SHOW_WAIT_MS = 2_000L
        const val MAX_FIELDS = 50
    }
}
