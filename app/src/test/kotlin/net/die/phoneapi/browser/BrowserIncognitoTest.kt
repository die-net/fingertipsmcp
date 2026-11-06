package net.die.phoneapi.browser

import kotlinx.coroutines.test.runTest
import net.die.phoneapi.model.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Opening a tab in Chrome's incognito profile, and refusing one that is not. */
@Suppress("InjectDispatcher") // Scripted sockets are read on a real dispatcher.
class BrowserIncognitoTest {
    private val harness = BrowserServiceTest()

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `opens an incognito tab`() {
        val started = mutableListOf<String>()
        val replies =
            ArrayDeque(
                listOf(
                    // A blank tab, to learn the normal tabs' context, and closing it.
                    created("blank"),
                    targets(page("a", "normal"), page("blank", "normal")),
                    reply("""{"success":true}"""),
                    // Before and after the shortcut, then loading the page.
                    targets(page("a", "normal")),
                    targets(page("a", "normal"), page("inc", "private")),
                    reply("""{"frameId":"inc"}"""),
                    // A second open knows the normal context already.
                    targets(page("a", "normal"), page("inc", "private")),
                    targets(page("a", "normal"), page("inc", "private"), page("inc2", "private")),
                    reply("""{"frameId":"inc2"}"""),
                )
            )
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                inFront = { false },
                open = { BrowserServiceTest.ScriptedSocket(replies.removeFirst()) },
                openPrivateTab = { started += it },
            )
        val target = harness.virtual { service.openTab("https://example.com/", incognito = true) }
        assertEquals("chrome_devtools_remote~inc", target.id)
        assertEquals("https://example.com/", target.url)
        assertEquals(true, target.tookScreen)
        val again = harness.virtual { service.openTab("https://example.com/", incognito = true) }
        assertEquals("chrome_devtools_remote~inc2", again.id)
        assertEquals(listOf("com.android.chrome", "com.android.chrome"), started)
        assertTrue(replies.isEmpty())
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `refuses a normal tab`() =
        runTest(harness.clock) {
            // Chrome answers the shortcut with a tab in the normal context, as if incognito were
            // off.
            val replies =
                ArrayDeque(
                    listOf(
                        created("blank"),
                        targets(page("blank", "normal")),
                        reply("""{"success":true}"""),
                        targets(),
                    )
                )
            val service =
                harness.service(
                    byteArrayOf(),
                    sockets = BrowserServiceTest.CHROME,
                    open = {
                        BrowserServiceTest.ScriptedSocket(
                            replies.removeFirstOrNull() ?: targets(page("new", "normal"))
                        )
                    },
                )
            // The polls' delays run in virtual time; only the scripted sockets are real.
            val error =
                try {
                    service.openTab("https://example.com/", incognito = true)
                    null
                } catch (e: ApiException) {
                    e
                }
            assertEquals("browser_unavailable", error?.error)
            assertTrue(error?.message.orEmpty().contains("incognito"), error?.message)
        }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `lists incognito tabs`() {
        val replies =
            ArrayDeque(
                listOf(
                    created("blank"),
                    targets(page("a", "normal"), page("blank", "normal")),
                    reply("""{"success":true}"""),
                    targets(page("a", "normal"), page("inc", "private")),
                )
            )
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = { BrowserServiceTest.ScriptedSocket(replies.removeFirst()) },
            )
        assertEquals(
            setOf("chrome_devtools_remote~inc"),
            harness.virtual { service.incognitoTabs() },
        )
        assertTrue(replies.isEmpty())
    }

    private fun reply(result: String): ByteArray =
        harness.switched() + harness.serverTextFrame("""{"id":1,"result":$result}""")

    private fun created(id: String): ByteArray = reply("""{"targetId":"$id"}""")

    private fun targets(vararg pages: String): ByteArray =
        reply("""{"targetInfos":[${pages.joinToString(",")}]}""")

    private fun page(id: String, context: String): String =
        """{"targetId":"$id","type":"page","url":"about:blank","browserContextId":"$context"}"""
}
