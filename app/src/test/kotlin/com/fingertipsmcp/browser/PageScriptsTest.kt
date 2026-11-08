package com.fingertipsmcp.browser

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Runs the page scripts' own tests, in `src/test/js`, with node's test runner. They load the
 * scripts from `src/main/assets` into a small fake DOM, so a change to a script is tested where it
 * is made. Gradle runs unit tests in the module's directory.
 */
class PageScriptsTest {
    @Test
    fun `page scripts pass their tests`() {
        val tests =
            File("src/test/js").listFiles { file -> file.name.endsWith(".test.js") }.orEmpty()
        assertTrue(tests.isNotEmpty(), "No tests in src/test/js")
        val log = File.createTempFile("page-scripts", ".log")
        try {
            val process =
                ProcessBuilder(listOf("node", "--test") + tests.sorted().map { it.path })
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .start()
            val exited = process.waitFor(SAFETY_TIMEOUT_S, TimeUnit.SECONDS)
            if (!exited) process.destroyForcibly()
            val output = log.readText()
            assertTrue(exited, output)
            assertEquals(0, process.exitValue(), output)
        } finally {
            log.delete()
        }
    }

    private companion object {
        /** Only for a hung node; the tests take well under a second. */
        const val SAFETY_TIMEOUT_S = 60L
    }
}
