package com.fingertipsmcp.server

import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.model.Scope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Sessions on a server with no HTTP module, as FingertipsMCP for Chrome runs with `--stdio`. */
class LocalSessionTest {
    @Test
    fun `stdio session owns its tab`() = runTest {
        val api = FakeApi(tabScope = backgroundScope)
        val sessions = mcpSessions(api.services, backgroundScope)
        val session = Holder.Session(sessions.startLocal("stdio", Scope.entries.toSet()))
        val opened = api.services.tabs.browserFor(session).openTab("https://example.com/")
        assertEquals(1, api.services.tabs.count(session))
        assertEquals(setOf(opened.id), api.tabStore.ids)
    }
}
