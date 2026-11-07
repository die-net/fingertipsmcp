package net.die.phoneapi.tree

import kotlinx.coroutines.test.runTest
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.PAGE_NODES_OFF
import net.die.phoneapi.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PageNodesTest {
    private val starts = ArrayList<String>()
    private var nodesOn = false
    private var nodesReturn = true

    private fun off() = ApiException(409, PAGE_NODES_OFF, "off")

    private val nodes =
        PageNodes(
            start = { pkg ->
                starts += pkg
                nodesOn = nodesReturn
            },
            bounds = {
                if (!nodesOn) throw off()
                Rect(0, 0, 1, 1)
            },
        )

    private fun find(): String = if (nodesOn) "field" else throw off()

    @Test
    fun `a start brings them back`() = runTest {
        assertEquals("field", nodes.restoring(CHROME) { find() })
        assertEquals(listOf(CHROME), starts)
    }

    @Test
    fun `nodes on starts nothing`() = runTest {
        nodesOn = true
        assertEquals("field", nodes.restoring(CHROME) { find() })
        assertEquals(emptyList<String>(), starts)
    }

    @Test
    fun `nodes that stay off fail once`() = runTest {
        nodesReturn = false
        assertEquals(PAGE_NODES_OFF, failure { nodes.restoring(CHROME) { find() } }.error)
        assertEquals(listOf(CHROME), starts)
    }

    @Test
    fun `other failures are final`() = runTest {
        var runs = 0
        val failed = failure {
            nodes.restoring<Unit>(CHROME) {
                runs++
                throw ApiException.notFound("No node matches the selector")
            }
        }
        assertEquals("not_found", failed.error)
        assertEquals(1, runs)
        assertEquals(emptyList<String>(), starts)
    }

    @Test
    fun `no package starts nothing`() = runTest {
        assertEquals(PAGE_NODES_OFF, failure { nodes.restoring(null) { find() } }.error)
        assertEquals(emptyList<String>(), starts)
    }

    private suspend fun failure(block: suspend () -> Unit): ApiException {
        try {
            block()
        } catch (e: ApiException) {
            return e
        }
        error("The call did not fail")
    }

    private companion object {
        const val CHROME = "com.android.chrome"
    }
}
