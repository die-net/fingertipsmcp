package com.fingertipsmcp.tree

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.TreeFound
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TreeReplyTest {
    @Test
    fun `returns the body`() {
        assertEquals("""{"found":true}""", treeReplyBody("""{"body":"{\"found\":true}"}"""))
        assertEquals("{}", treeReplyBody("{}"))
    }

    @Test
    fun `passes helper errors through`() {
        val e =
            assertThrows<ApiException> {
                treeReplyBody("""{"status":404,"error":"stale_ref","message":"Node e3 is gone"}""")
            }
        assertEquals(404, e.status)
        assertEquals("stale_ref", e.error)
    }

    @Test
    fun `malformed reply is a 503`() {
        // A reply that does not parse is the helper's fault, not a bad request from the client.
        for (reply in listOf("not json", """{"status":"x"}""")) {
            val e = assertThrows<ApiException> { treeReplyBody(reply) }
            assertEquals(503, e.status)
            assertEquals("helper_error", e.error)
        }
        val body = assertThrows<ApiException> { decodeHelperJson(TreeFound.serializer(), "[]") }
        assertEquals("helper_error", body.error)
    }
}
