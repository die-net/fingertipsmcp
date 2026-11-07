package net.die.phoneapi.tree

import kotlinx.coroutines.flow.StateFlow
import net.die.phoneapi.core.UiService
import net.die.phoneapi.helper.tree.ActionNames
import net.die.phoneapi.input.TouchInput
import net.die.phoneapi.input.asResult
import net.die.phoneapi.model.ActionMode
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.FindResult
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.SnapshotOptions
import net.die.phoneapi.model.UiSnapshot

/** [UiService] that asks the helper for the tree and injects real clicks itself. */
class HelperUiService(
    private val prepare: suspend (Boolean) -> Boolean,
    private val tree: TreeSession,
    private val touch: TouchInput,
    private val seq: StateFlow<Long>,
) : UiService {
    override suspend fun snapshot(options: SnapshotOptions): UiSnapshot {
        if ((options.maxNodes ?: 1) < 1 || (options.maxChars ?: 1) < 1) {
            throw ApiException.badRequest("maxNodes and maxChars must be at least 1")
        }
        prepare(options.autoWake)
        return tree.snapshot(options)
    }

    override suspend fun find(request: FindRequest): FindResult {
        prepare(true)
        return tree.find(request)
    }

    override suspend fun act(ref: String, request: NodeActionRequest): ActionResult {
        val woke = prepare(request.autoWake)
        val action = request.action
        val click = action.equals(ActionNames.CLICK, ignoreCase = true)
        val longClick = action.equals(ActionNames.LONG_CLICK, ignoreCase = true)
        val result =
            try {
                if (request.mode == ActionMode.REAL && (click || longClick)) {
                    val target = tree.touchTarget(ref, request.force)
                    (if (click) touch.tap(target) else touch.longPress(target)).asResult()
                } else {
                    tree.act(ref, action, request.text)
                }
            } finally {
                // A touch the system cancelled partway may still have changed the screen.
                tree.invalidate()
            }
        return result.copy(woke = woke, seq = seq.value)
    }
}
