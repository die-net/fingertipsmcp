package com.fingertipsmcp.chrome

import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.BrowserTarget
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LastTabTest {
    private class Tabs(vararg ids: String) {
        val open = ids.toMutableList()
        val calls = ArrayList<String>()

        @Suppress("UNCHECKED_CAST")
        val service: BrowserService =
            Proxy.newProxyInstance(
                BrowserService::class.java.classLoader,
                arrayOf(BrowserService::class.java),
            ) { _, method, args ->
                calls += method.name
                when (method.name) {
                    "targets" ->
                        open.map { BrowserTarget(id = it, type = "page", socket = "chrome") }
                    "openTab" -> {
                        open += "new"
                        BrowserTarget(id = "new", type = "page", socket = "chrome")
                    }
                    "closeTab" -> {
                        open -= args[0] as String
                        ActionResult(ok = true)
                    }
                    else -> error("unexpected ${method.name}")
                }
            } as BrowserService
    }

    @Test
    fun `keeps a tab open`() {
        val tabs = Tabs("chrome~a")
        runBlocking { LastTab(tabs.service).closeTab("chrome~a") }
        assertEquals(listOf("targets", "openTab", "closeTab"), tabs.calls)
        assertEquals(listOf("new"), tabs.open)
    }

    @Test
    fun `closes one of several`() {
        val tabs = Tabs("chrome~a", "chrome~b")
        runBlocking { LastTab(tabs.service).closeTab("chrome~a") }
        assertEquals(listOf("targets", "closeTab"), tabs.calls)
        assertEquals(listOf("chrome~b"), tabs.open)
    }
}
