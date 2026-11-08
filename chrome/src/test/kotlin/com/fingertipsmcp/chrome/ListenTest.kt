package com.fingertipsmcp.chrome

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.PairingInfo
import com.fingertipsmcp.server.TokenStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import kotlinx.serialization.serializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Where `serve` listens, what `token create` pairs with, and taking a Unix socket's path. */
class ListenTest {
    // Short, since a Unix socket's path is at most 103 bytes.
    private val dir: File = Files.createTempDirectory(File("/tmp").toPath(), "ft").toFile()
    private val paths =
        ChromePaths(
            env = mapOf("XDG_CONFIG_HOME" to "$dir/c", "XDG_DATA_HOME" to "$dir/d"),
            home = dir,
        )

    @AfterEach
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `keeps and pairs a given port`() {
        assertEquals(
            Listen.Tcp(DEFAULT_HOST, 4100),
            listenFor(ServeOptions(port = 4100), paths.config),
        )
        assertEquals(Listen.Tcp(DEFAULT_HOST, 4100), listenFor(ServeOptions(), paths.config))
        assertEquals(4100, createPairing().port)
    }

    @Test
    fun `keeps and pairs a given host`() {
        val port = (listenFor(ServeOptions(), paths.config) as Listen.Tcp).port
        assertEquals(Listen.Tcp("::1", port), listenFor(ServeOptions(host = "::1"), paths.config))
        assertEquals("::1", createPairing().host)
        // A server on every address is reached on loopback.
        listenFor(ServeOptions(host = "0.0.0.0"), paths.config)
        assertEquals(DEFAULT_HOST, createPairing().host)
    }

    @Test
    fun `refuses pairing for a socket`() {
        val port = (listenFor(ServeOptions(), paths.config) as Listen.Tcp).port
        assertEquals(
            Listen.Unix("/tmp/x.sock"),
            listenFor(ServeOptions(unixSocket = "/tmp/x.sock"), paths.config),
        )
        assertEquals(Listen.Unix("/tmp/x.sock"), listenFor(ServeOptions(), paths.config))
        assertThrows(UsageException::class.java) { createPairing() }
        assertTrue(listTokens().isEmpty(), "refused before a token is minted")
        // --no-pairing still prints a token.
        sink().use {
            createToken(Command.TokenCreate("n", DEFAULT_SCOPES, writePairing = false), paths, it)
        }
        assertEquals(1, listTokens().size)
        // --port goes back to TCP on the kept port.
        assertEquals(
            Listen.Tcp(DEFAULT_HOST, port),
            listenFor(ServeOptions(port = port), paths.config),
        )
    }

    @Test
    fun `replaces corrupt settings`() {
        paths.ensure()
        File(paths.config, "settings.json").writeText("{not json")
        val made = loadSettings(paths.config)
        assertEquals(made, loadSettings(paths.config))
    }

    @Test
    fun `replaces a dead socket`() {
        val socket = File(dir, "s.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket.toPath()))
        }
        assertTrue(socket.exists(), "closing a socket leaves its file")
        claimUnixSocket(socket)
        assertFalse(socket.exists())
    }

    @Test
    fun `refuses a live socket`() {
        val socket = File(dir, "s.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket.toPath()))
            val error = assertThrows(UsageException::class.java) { claimUnixSocket(socket) }
            assertTrue("listening" in error.message.orEmpty(), error.message)
            assertTrue(socket.exists())
        }
    }

    @Test
    fun `refuses a file not a socket`() {
        val file = File(dir, "notes.txt").apply { writeText("keep") }
        assertThrows(UsageException::class.java) { claimUnixSocket(file) }
        assertEquals("keep", file.readText())
        claimUnixSocket(File(dir, "missing.sock"))
        assertNotEquals(0, file.length())
    }

    private fun createPairing(): PairingInfo {
        sink().use {
            createToken(Command.TokenCreate("p", DEFAULT_SCOPES, writePairing = true), paths, it)
        }
        return ApiJson.decodeFromString(serializer<PairingInfo>(), paths.pairing.readText())
    }

    private fun listTokens() = TokenStore(paths.config).list()

    private fun sink() = PrintStream(ByteArrayOutputStream())
}
