package net.die.phoneapi.helper.compat

import net.die.phoneapi.helper.StartResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ShellActivityTest {
    private fun code(value: Int) = StartResult().apply { code = value }

    @Test
    fun `started gives the top task`() {
        assertEquals(TASK, shellActivityTask({ code(START_SUCCESS) }, { TASK }))
    }

    @Test
    fun `task brought back counts`() {
        assertEquals(TASK, shellActivityTask({ code(START_TASK_TO_FRONT) }, { TASK }))
    }

    /** `am` exits 0 for all of these, and an older activity on top then looked like this one. */
    @Test
    fun `refused start claims no task`() {
        var lookups = 0
        REFUSALS.forEach { refusal ->
            val task =
                shellActivityTask({ code(refusal) }) {
                    lookups++
                    TASK
                }
            assertNull(task, "code $refusal")
        }
        assertEquals(0, lookups)
    }

    @Test
    fun `thrown refusal claims no task`() {
        val refused =
            StartResult().apply {
                code = START_PERMISSION_DENIED
                refusal = "Permission Denial"
            }
        assertNull(shellActivityTask({ refused }, { TASK }))
    }

    @Test
    fun `other activity on top is null`() {
        assertNull(shellActivityTask({ code(START_SUCCESS) }, { null }))
    }

    /** The code means nothing here, so a stale value must not decide. */
    @Test
    fun `timed out wait still looks`() {
        val late =
            StartResult().apply {
                code = START_PERMISSION_DENIED
                timedOut = true
            }
        assertEquals(TASK, shellActivityTask({ late }, { TASK }))
    }

    @Test
    fun `no start method gives no task`() {
        assertNull(shellActivityTask({ error("no startActivityAndWait") }, { TASK }))
    }

    @Test
    fun `failed lookup gives no task`() {
        assertNull(shellActivityTask({ code(START_SUCCESS) }, { error("no getTasks") }))
    }

    private companion object {
        const val TASK = 42
        const val START_SUCCESS = 0
        const val START_TASK_TO_FRONT = 2
        const val START_PERMISSION_DENIED = -94
        /** Not resolved, class not found, denied, canceled, switches canceled, aborted. */
        val REFUSALS = listOf(-91, -92, START_PERMISSION_DENIED, -96, 100, 102)
    }
}
