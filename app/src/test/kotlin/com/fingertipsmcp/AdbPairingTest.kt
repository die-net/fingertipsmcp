package com.fingertipsmcp

import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.server.TokenStore
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdbPairingTest {
    private val dir: File = Files.createTempDirectory("pairing").toFile()
    private val tokens = TokenStore(dir)
    private val pairing = AdbPairing(tokens) { PORT }

    @AfterEach
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `the shell gets a working token`() {
        val info = pairing.pair(SHELL_UID, "laptop", "observe,stream")
        val token = tokens.authenticate(info.token)
        assertEquals("laptop", token?.name)
        assertEquals(setOf(Scope.OBSERVE, Scope.STREAM), token?.scopes)
        assertEquals(PORT, info.port)
        assertEquals("127.0.0.1", info.host)
        assertEquals("laptop", info.name)
    }

    @Test
    fun `root gets every scope`() {
        val info = pairing.pair(ROOT_UID, null, null)
        assertEquals(Scope.entries.toSet(), tokens.authenticate(info.token)?.scopes)
        assertEquals("adb", info.name)
    }

    @Test
    fun `an app's uid mints nothing`() {
        assertThrows(SecurityException::class.java) { pairing.pair(APP_UID, "app", null) }
        assertThrows(SecurityException::class.java) { pairing.pair(SYSTEM_UID, "system", null) }
        assertEquals(emptyList<Any>(), tokens.list())
    }

    @Test
    fun `a bad scope list mints nothing`() {
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                pairing.pair(SHELL_UID, "laptop", "observe,nope")
            }
        assertTrue(thrown.message.orEmpty().contains("nope"))
        assertEquals(emptyList<Any>(), tokens.list())
    }

    @Test
    fun `parses scopes`() {
        assertEquals(Scope.entries.toSet(), taken(parseScopeList(null)))
        assertEquals(setOf(Scope.OBSERVE, Scope.CONTROL), taken(parseScopeList("observe,control")))
        assertEquals(
            setOf(Scope.OBSERVE, Scope.CONTROL),
            taken(parseScopeList(" Observe , CONTROL ")),
        )
        assertEquals(setOf(Scope.BROWSER), taken(parseScopeList("browser,")))
    }

    @Test
    fun `rejects bad scopes`() {
        assertTrue(parseScopeList("") is Parse.Error)
        assertTrue(parseScopeList("   ") is Parse.Error)
        assertTrue(parseScopeList(",") is Parse.Error)
        val unknown = parseScopeList("bogus")
        assertTrue(unknown is Parse.Error)
        assertTrue((unknown as Parse.Error).message.contains("bogus"))
        assertTrue(parseScopeList("observe,nope") is Parse.Error)
    }

    private fun <T> taken(parsed: Parse<T>): T {
        assertTrue(parsed is Parse.Ok)
        return (parsed as Parse.Ok).value
    }

    private companion object {
        const val PORT = 38_068
        const val ROOT_UID = 0
        const val SYSTEM_UID = 1000
        const val APP_UID = 10_123
    }
}
