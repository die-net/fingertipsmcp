package net.die.phoneapi.browser

import net.die.phoneapi.model.ConsoleEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Collecting a page's console for a while, without what enabling it replays. */
@Suppress("MissingUseCall") // The session closes on use, and the test reads the socket after.
class ConsoleLogTest {
    private val harness = BrowserServiceTest()

    @Test
    fun `keeps entries after enabling`() {
        val page =
            harness.socket(
                harness.switched() +
                    console("log", "replayed by Runtime.enable") +
                    reply(1) +
                    log("error", "replayed by Log.enable", "network") +
                    reply(2) +
                    console("warning", "new") +
                    log("info", "also a console call", "console-api") +
                    log("error", "net::ERR_FAILED", "network")
            )
        val result =
            harness.clock.run {
                CdpSession(page.input, page.output, harness.clock) { BrowserServiceTest.KEY }
                    .use { cdp ->
                        cdp.open("/devtools/page/t")
                        collectLogs(cdp, timeoutMs = 1_000)
                    }
            }
        assertEquals(
            listOf(ConsoleEntry("warning", "new"), ConsoleEntry("error", "net::ERR_FAILED")),
            result.entries,
        )
        assertEquals(listOf("Runtime.enable", "Log.enable"), page.methods())
    }

    private fun reply(id: Int) = harness.serverTextFrame("""{"id":$id,"result":{}}""")

    private fun console(type: String, text: String) =
        harness.serverTextFrame(
            """{"method":"Runtime.consoleAPICalled","params":{"type":"$type",""" +
                """"args":[{"type":"string","value":"$text"}]}}"""
        )

    private fun log(level: String, text: String, source: String) =
        harness.serverTextFrame(
            """{"method":"Log.entryAdded","params":{"entry":{"level":"$level",""" +
                """"text":"$text","source":"$source"}}}"""
        )
}
