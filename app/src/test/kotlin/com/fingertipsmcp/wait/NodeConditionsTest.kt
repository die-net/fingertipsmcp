package com.fingertipsmcp.wait

import com.fingertipsmcp.helper.tree.UiStates
import com.fingertipsmcp.model.NodeState
import com.fingertipsmcp.model.Rect
import com.fingertipsmcp.model.UiNode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeConditionsTest {
    private fun node(vararg states: String) =
        UiNode(ref = "e1", role = "button", bounds = Rect(0, 0, 10, 10), states = states.toList())

    private fun test(state: NodeState, node: UiNode?) = NodeConditions.test(state)(node)

    @Test
    fun `tests presence and absence`() {
        assertTrue(test(NodeState.PRESENT, node()))
        assertFalse(test(NodeState.PRESENT, null))
        assertTrue(test(NodeState.ABSENT, null))
        assertFalse(test(NodeState.ABSENT, node()))
    }

    @Test
    fun `tests node states`() {
        assertTrue(test(NodeState.CHECKED, node(UiStates.CHECKABLE, UiStates.CHECKED)))
        assertFalse(test(NodeState.CHECKED, node(UiStates.CHECKABLE)))
        assertTrue(test(NodeState.UNCHECKED, node(UiStates.CHECKABLE)))
        // A node that can't be checked at all is neither checked nor unchecked.
        assertFalse(test(NodeState.UNCHECKED, node()))
        assertFalse(test(NodeState.CHECKED, null))
        assertTrue(test(NodeState.ENABLED, node()))
        assertFalse(test(NodeState.ENABLED, node(UiStates.DISABLED)))
        assertTrue(test(NodeState.DISABLED, node(UiStates.DISABLED)))
        assertTrue(test(NodeState.FOCUSED, node(UiStates.FOCUSED)))
        assertTrue(test(NodeState.SELECTED, node(UiStates.SELECTED)))
    }

    @Test
    fun `scopes presence to the tree`() {
        assertTrue(NodeConditions.includeInvisible(NodeState.PRESENT))
        assertTrue(NodeConditions.includeInvisible(NodeState.ABSENT))
        assertFalse(NodeConditions.includeInvisible(NodeState.VISIBLE))
        assertFalse(NodeConditions.includeInvisible(NodeState.CHECKED))
    }
}
