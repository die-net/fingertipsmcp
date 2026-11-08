package com.fingertipsmcp.chrome

import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("InjectDispatcher") // Arguments only; nothing runs on it.
class ChromeLocatorTest {
    private fun locator(os: String, present: Set<String>, env: Map<String, String> = emptyMap()) =
        ChromeLocator(
            env = mapOf("PATH" to "/usr/local/bin:/usr/bin") + env,
            osName = os,
            home = File("/home/u"),
            runnable = { it.path in present },
        )

    @Test
    fun `prefers google chrome`() {
        val found =
            locator("Linux", setOf("/usr/bin/chromium", "/usr/bin/google-chrome-stable")).find(null)
        assertEquals(BrowserBinary(File("/usr/bin/google-chrome-stable"), chromium = false), found)
    }

    @Test
    fun `falls back to chromium`() {
        val found = locator("Linux", setOf("/usr/bin/chromium")).find(null)
        assertTrue(found.chromium)
        assertEquals(File("/usr/bin/chromium"), found.path)
    }

    @Test
    fun `finds chrome on a mac`() {
        val app = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
        val found = locator("Mac OS X", setOf(app)).find(null)
        assertEquals(File(app), found.path)
        assertFalse(found.chromium)
    }

    @Test
    fun `an explicit path wins`() {
        val found =
            locator(
                    "Linux",
                    setOf("/opt/google/chrome/chrome", "/x/chromium"),
                    mapOf("FINGERTIPS_CHROME" to "/x/chromium"),
                )
                .find(null)
        assertEquals(File("/x/chromium"), found.path)
        assertTrue(found.chromium)
        val flag =
            locator("Linux", setOf("/opt/google/chrome/chrome")).find("/opt/google/chrome/chrome")
        assertFalse(flag.chromium)
    }

    @Test
    fun `says what is missing`() {
        assertThrows(LaunchException::class.java) { locator("Linux", emptySet()).find(null) }
        assertThrows(LaunchException::class.java) { locator("Linux", emptySet()).find("/nope") }
    }

    @Test
    fun `launches with minimal flags`() {
        val args = launcher(headless = false, hideAutomation = true).arguments()
        assertEquals(
            listOf(
                "--remote-debugging-pipe",
                "--user-data-dir=/p",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-blink-features=AutomationControlled",
                "--no-startup-window",
                "--lang=de",
            ),
            args,
        )
        val banned = listOf("--enable-automation", "--no-sandbox", "--disable-gpu")
        assertTrue(args.none { arg -> banned.any { arg.startsWith(it) } })
    }

    @Test
    fun `can show automation`() {
        val args = launcher(headless = false, hideAutomation = false).arguments()
        assertFalse(args.any { it.startsWith("--disable-blink-features") })
        assertTrue("--no-startup-window" in args)
    }

    @Test
    fun `headless opens its own tab`() {
        val args = launcher(headless = true, hideAutomation = true).arguments()
        assertTrue("--headless=new" in args)
        assertFalse("--no-startup-window" in args)
    }

    private fun launcher(headless: Boolean, hideAutomation: Boolean) =
        ChromeLauncher(
            BrowserBinary(File("/c"), chromium = false),
            profile = File("/p"),
            headless = headless,
            extraArgs = listOf("--lang=de"),
            logFile = File("/l"),
            io = Dispatchers.IO,
            hideAutomation = hideAutomation,
        )
}
