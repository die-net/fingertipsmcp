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
            environment = mapOf("PATH" to "/usr/local/bin:/usr/bin") + env,
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
    fun `finds chrome on windows`() {
        val env =
            mapOf(
                "PATH" to """C:\Windows;C:\tools""",
                "ProgramFiles" to """C:\Program Files""",
                "LOCALAPPDATA" to """C:\Users\u\AppData\Local""",
            )
        fun install(root: String, vararg vendor: String) =
            (vendor.toList() + "Application" + "chrome.exe").fold(File(root), ::File)
        val machine = install("""C:\Program Files""", "Google", "Chrome")
        val perUser = install("""C:\Users\u\AppData\Local""", "Google", "Chrome")
        val chromium = install("""C:\Users\u\AppData\Local""", "Chromium")
        fun windows(vararg present: File) =
            ChromeLocator(env, "Windows 11", File("""C:\Users\u""")) { it in present }

        assertEquals(BrowserBinary(machine, chromium = false), windows(machine, perUser).find(null))
        assertEquals(BrowserBinary(perUser, chromium = false), windows(perUser).find(null))
        assertEquals(BrowserBinary(chromium, chromium = true), windows(chromium).find(null))
        // A bare name on PATH may leave off .exe.
        val onPath = File("""C:\tools""", "chrome.exe")
        assertEquals(onPath, windows(onPath).find("chrome").path)
    }

    @Test
    fun `ignores case of windows env`() {
        val chrome =
            listOf("Google", "Chrome", "Application", "chrome.exe")
                .fold(File("""C:\Program Files"""), ::File)
        val onPath = File("""C:\tools""", "chrome.exe")
        // Git Bash passes names in capitals, and Explorer as Windows spells them.
        val gitBash = mapOf("PROGRAMFILES" to """C:\Program Files""", "PATH" to """C:\tools""")
        val explorer = mapOf("ProgramFiles" to """C:\Program Files""", "Path" to """C:\tools""")
        for (env in listOf(gitBash, explorer)) {
            val windows =
                ChromeLocator(env, "Windows 11", File("""C:\Users\u""")) {
                    it == chrome || it == onPath
                }
            assertEquals(chrome, windows.find(null).path, "$env")
            assertEquals(onPath, windows.find("chrome").path, "$env")
        }
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
