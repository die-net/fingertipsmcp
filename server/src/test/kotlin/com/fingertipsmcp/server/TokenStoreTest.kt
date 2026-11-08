package com.fingertipsmcp.server

import com.fingertipsmcp.model.Scope
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Two stores on one directory, as `fingertips-chrome token create` and a running server are: each
 * sees what the other wrote, and neither's write drops the other's.
 */
class TokenStoreTest {
    private val dir: File = Files.createTempDirectory("tokens").toFile()
    private var now = 1_000_000L
    private val server = TokenStore(dir) { now }
    private val cli = TokenStore(dir) { now }

    @AfterEach
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `accepts a token made elsewhere`() {
        assertNull(server.authenticate("ft_nothing"))
        val created = cli.create("client", setOf(Scope.OBSERVE))
        assertEquals(created.info.id, server.authenticate(created.token)?.id)
    }

    @Test
    fun `refuses a revoked token`() {
        val created = cli.create("client", setOf(Scope.OBSERVE))
        assertNotNull(server.authenticate(created.token))
        cli.revoke(created.info.id)
        assertNull(server.authenticate(created.token))
    }

    @Test
    fun `last use keeps others' tokens`() {
        val first = server.create("first", setOf(Scope.OBSERVE))
        assertNotNull(server.authenticate(first.token))
        val second = cli.create("second", setOf(Scope.CONTROL))
        now += 61_000
        assertNotNull(server.authenticate(first.token))
        val listed = TokenStore(dir).list()
        assertEquals(setOf(first.info.id, second.info.id), listed.map { it.id }.toSet())
        assertEquals(now, listed.single { it.id == first.info.id }.lastUsedAtMs)
    }

    @Test
    fun `changes keep unsaved last use`() {
        val first = server.create("first", setOf(Scope.OBSERVE))
        now += 1_000
        assertNotNull(server.authenticate(first.token))
        now += 1_000
        assertNotNull(server.authenticate(first.token))
        server.create("second", setOf(Scope.CONTROL))
        assertEquals(now, cli.list().single { it.id == first.info.id }.lastUsedAtMs)
    }

    @Test
    fun `a found change bumps revision`() {
        assertEquals(emptyList<Any>(), server.list())
        val other = cli.create("other", setOf(Scope.OBSERVE))
        val before = server.revision.value
        assertEquals(false, server.revoke("missing"))
        assertEquals(before + 1, server.revision.value)
        assertEquals(listOf(other.info.id), server.list().map { it.id })
        assertEquals(before + 1, server.revision.value)
    }

    @Test
    fun `scope changes reach this store`() {
        val created = server.create("client", setOf(Scope.OBSERVE))
        cli.setScopes(created.info.id, setOf(Scope.CONTROL))
        assertEquals(setOf(Scope.CONTROL), server.authenticate(created.token)?.scopes)
        val before = server.revision.value
        assertEquals(true, cli.revoke(created.info.id))
        assertEquals(emptyList<Any>(), server.list())
        assertEquals(before + 1, server.revision.value)
        assertEquals(false, server.revoke(created.info.id))
    }
}
