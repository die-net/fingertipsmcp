package com.fingertipsmcp.chrome

import com.fingertipsmcp.model.Scope
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OptionsTest {
    @Test
    fun `serves by default`() {
        assertEquals(Command.Serve(ServeOptions()), parseCommand(emptyList()))
    }

    @Test
    fun `reads serve options`() {
        val command =
            parseCommand(
                listOf(
                    "--port",
                    "4100",
                    "--chrome",
                    "/opt/chrome",
                    "--headless",
                    "--chrome-arg",
                    "--lang=fr",
                    "--chrome-arg",
                    "--window-size=1280,800",
                )
            )
        val options = (command as Command.Serve).options
        assertEquals(4100, options.port)
        assertEquals("/opt/chrome", options.chrome)
        assertTrue(options.headless)
        assertEquals(listOf("--lang=fr", "--window-size=1280,800"), options.chromeArgs)
        assertFalse(options.stdio)
        assertFalse(options.showAutomation)
        assertTrue(
            (parseCommand(listOf("--show-automation")) as Command.Serve).options.showAutomation
        )
    }

    @Test
    fun `reads stdio and unix`() {
        val stdio = parseCommand(listOf("--stdio")) as Command.Serve
        assertTrue(stdio.options.stdio)
        val unix = parseCommand(listOf("serve", "--unix", "/tmp/d.sock")) as Command.Serve
        assertEquals("/tmp/d.sock", unix.options.unixSocket)
        assertThrows(UsageException::class.java) {
            parseCommand(listOf("--unix", "/tmp/d.sock", "--port", "1"))
        }
    }

    @Test
    fun `reads token commands`() {
        assertEquals(
            Command.TokenCreate("fingertips-chrome", DEFAULT_SCOPES, writePairing = true),
            parseCommand(listOf("token", "create")),
        )
        assertEquals(
            Command.TokenCreate("ci", setOf(Scope.OBSERVE, Scope.ADMIN), writePairing = false),
            parseCommand(
                listOf(
                    "token",
                    "create",
                    "--name",
                    "ci",
                    "--scopes",
                    "observe, admin",
                    "--no-pairing",
                )
            ),
        )
        assertEquals(Command.TokenRevoke("abc"), parseCommand(listOf("token", "revoke", "abc")))
        assertEquals(Command.TokenList, parseCommand(listOf("token", "list")))
    }

    @Test
    fun `rejects bad input`() {
        listOf(
                listOf("--port"),
                listOf("--port", "70000"),
                listOf("--nope"),
                listOf("token"),
                listOf("token", "create", "--scopes", "root"),
                listOf("token", "revoke"),
            )
            .forEach { args ->
                assertThrows(UsageException::class.java, { parseCommand(args) }, "$args")
            }
    }

    @Test
    fun `rejects options made moot`() {
        listOf(
                listOf("--unix", "/tmp/d.sock", "--port", "1"),
                listOf("--port", "1", "--unix", "/tmp/d.sock"),
                listOf("--unix", "/tmp/d.sock", "--host", "127.0.0.1"),
                listOf("--stdio", "--port", "1"),
                listOf("--stdio", "--unix", "/tmp/d.sock"),
                listOf("--host", "::1", "--stdio"),
                listOf("--attach", "9222", "--chrome", "/opt/chrome"),
                listOf("--profile", "/tmp/p", "--attach", "9222"),
                listOf("--attach", "9222", "--headless"),
                listOf("--attach", "9222", "--chrome-arg", "--lang=fr"),
                listOf("--attach", "9222", "--show-automation"),
            )
            .forEach { args ->
                assertThrows(UsageException::class.java, { parseCommand(args) }, "$args")
            }
        val attach = parseCommand(listOf("--attach", "9222", "--port", "4100")) as Command.Serve
        assertEquals("9222", attach.options.attach)
        val stdio = parseCommand(listOf("--stdio", "--profile", "/tmp/p")) as Command.Serve
        assertEquals("/tmp/p", stdio.options.profile)
    }

    @Test
    fun `follows xdg`() {
        val home = File("/home/u")
        val linux = ChromePaths(environment = emptyMap(), home = home, osName = "Linux")
        assertEquals(File("/home/u/.config/fingertips-chrome/pairing.json"), linux.pairing)
        assertEquals(File("/home/u/.local/share/fingertips-chrome/chrome-profile"), linux.profile)
        val xdg =
            ChromePaths(
                environment = mapOf("XDG_CONFIG_HOME" to "/c", "XDG_DATA_HOME" to "/d"),
                home = home,
                osName = "Linux",
            )
        assertEquals(File("/c/fingertips-chrome/pairing.json"), xdg.pairing)
        assertEquals(File("/d/fingertips-chrome/chrome-profile"), xdg.profile)
        val mac = ChromePaths(environment = emptyMap(), home = home, osName = "Mac OS X")
        assertEquals(File("/home/u/.config/fingertips-chrome"), mac.config)
        assertEquals(
            File("/home/u/Library/Application Support/fingertips-chrome/chrome-profile"),
            mac.profile,
        )
        val local = """C:\Users\u\AppData\Local"""
        val windows =
            ChromePaths(
                environment = mapOf("LOCALAPPDATA" to local),
                home = home,
                osName = "Windows 11",
            )
        assertEquals(File(local, "fingertips-chrome"), windows.config)
        assertEquals(File(File(local, "fingertips-chrome"), "chrome-profile"), windows.profile)
        val mixedCase =
            ChromePaths(
                environment = mapOf("LocalAppData" to local),
                home = home,
                osName = "Windows 11",
            )
        assertEquals(File(local, "fingertips-chrome"), mixedCase.config)
        val noLocal = ChromePaths(environment = emptyMap(), home = home, osName = "Windows 10")
        assertEquals(File("/home/u/AppData/Local/fingertips-chrome"), noLocal.config)
    }
}
