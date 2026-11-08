package com.fingertipsmcp.wait

import com.fingertipsmcp.helper.tree.UiStates
import com.fingertipsmcp.model.NodeState
import com.fingertipsmcp.model.UiNode

/**
 * The node states a wait condition can ask for, tested against the best match for its selector (or
 * null when nothing matched).
 */
internal object NodeConditions {
    private val TESTS: Map<NodeState, (UiNode?) -> Boolean> =
        mapOf(
            NodeState.PRESENT to { node -> node != null },
            NodeState.ABSENT to { node -> node == null },
            NodeState.VISIBLE to { node -> node != null },
            NodeState.ENABLED to { node -> node.has { UiStates.DISABLED !in it } },
            NodeState.DISABLED to { node -> node.has { UiStates.DISABLED in it } },
            NodeState.CHECKED to { node -> node.has { UiStates.CHECKED in it } },
            NodeState.UNCHECKED to
                { node ->
                    node.has { UiStates.CHECKABLE in it && UiStates.CHECKED !in it }
                },
            NodeState.FOCUSED to { node -> node.has { UiStates.FOCUSED in it } },
            NodeState.SELECTED to { node -> node.has { UiStates.SELECTED in it } },
        )

    fun test(state: NodeState): (UiNode?) -> Boolean = TESTS.getValue(state)

    /** `present` and `absent` look at the whole tree; every other state needs a visible node. */
    fun includeInvisible(state: NodeState): Boolean =
        state == NodeState.PRESENT || state == NodeState.ABSENT
}

private inline fun UiNode?.has(predicate: (List<String>) -> Boolean): Boolean =
    this != null && predicate(states)
