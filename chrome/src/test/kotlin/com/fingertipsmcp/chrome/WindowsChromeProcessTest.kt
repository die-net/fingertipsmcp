package com.fingertipsmcp.chrome

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What of [WindowsChromeProcess] runs off Windows: quoting Chrome's arguments so
 * `CommandLineToArgvW` splits them back as they were, and the Win32 structures' layout.
 */
class WindowsChromeProcessTest {
    @Test
    fun `lays out win32 structures`() {
        // STARTUPINFOEXW and PROCESS_INFORMATION on x64 and ARM64.
        assertEquals(112, StartupInfoEx().size())
        assertEquals(24, ProcessInformation().size())
    }

    @Test
    fun `leaves plain arguments alone`() {
        assertEquals(
            """C:\c\chrome.exe --no-first-run --user-data-dir=C:\p""",
            windowsCommandLine(
                listOf("""C:\c\chrome.exe""", "--no-first-run", """--user-data-dir=C:\p""")
            ),
        )
    }

    @Test
    fun `quotes spaces and empties`() {
        assertEquals(
            """"C:\Program Files\Google\Chrome\Application\chrome.exe" "--user-data-dir=C:\Users\a b\p" """"",
            windowsCommandLine(
                listOf(
                    """C:\Program Files\Google\Chrome\Application\chrome.exe""",
                    """--user-data-dir=C:\Users\a b\p""",
                    "",
                )
            ),
        )
    }

    @Test
    fun `escapes quotes`() {
        // a"b, a\"b, and a trailing backslash inside quotes.
        assertEquals(
            """"a\"b" "a\\\"b" "C:\a b\\"""",
            windowsCommandLine(listOf("a\"b", "a\\\"b", "C:\\a b\\")),
        )
    }
}
