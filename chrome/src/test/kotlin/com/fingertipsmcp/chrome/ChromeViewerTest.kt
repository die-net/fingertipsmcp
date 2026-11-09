package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpBrowserConnection
import com.fingertipsmcp.browser.DesktopPointer
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.PointerOp
import com.fingertipsmcp.stream.VideoSpec
import com.fingertipsmcp.stream.VideoViewer
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent runs what is due on the virtual clock.
class ChromeViewerTest {
    @TempDir lateinit var home: File

    private val browser = ShowingBrowser(listOf("PAGE"), setOf("PAGE"))

    @Test fun `failed video start stops`() = assertStops { viewer -> viewer.watchVideo(SPEC) {} }

    @Test fun `failed audio start stops`() = assertStops { viewer -> viewer.watchAudio {} }

    /**
     * A viewer whose capture fails to start because Chrome is not up yet, then one that starts it
     * and leaves. Capture stops as the second leaves, since the first counts as gone.
     */
    private fun assertStops(watch: suspend (ChromeViewer) -> Unit) = runTest {
        FakeChrome().use { chrome ->
            CdpBrowserConnection(chrome, StandardTestDispatcher(testScheduler)).use { connection ->
                connection.start()
                var starts = 0
                val viewer =
                    ChromeViewer(
                        connection = {
                            starts += 1
                            if (starts == 1) {
                                throw ApiException.unavailable("chrome_stopped", "Not yet")
                            }
                            connection
                        },
                        browser = ShowingBrowser(listOf("PAGE"), setOf("PAGE")),
                        extensionHome = home,
                        scope = backgroundScope,
                        pointer = DesktopPointer(),
                    )
                assertThrows<ApiException> { watch(viewer) }
                watch(viewer)
                assertTrue(chrome.evaluated.any { it.startsWith("fingertipsViewer.capture(") })
                assertTrue(
                    "fingertipsViewer.stop()" in chrome.evaluated,
                    chrome.evaluated.toString(),
                )
            }
        }
    }

    /** Chrome 125 has no Extensions domain, so the viewer can't load its extension. */
    @Test
    fun `no Extensions domain refused`() = runTest {
        assertRefused(
            this,
            "Extensions.loadUnpacked",
        ) {
            missing += setOf("Extensions.loadUnpacked", "Extensions.triggerAction")
        }
    }

    /**
     * Chrome 126 to 145 load an unpacked extension but can't run its action, so the extension would
     * capture nothing. It is not loaded.
     */
    @Test
    fun `no trigger action refused`() = runTest {
        assertRefused(
            this,
            "Extensions.triggerAction",
        ) {
            missing += "Extensions.triggerAction"
        }
    }

    /** Chrome 146 to 148 have both commands but refuse the load over the pipe. */
    @Test
    fun `refused extension load`() = runTest {
        assertRefused(
            this,
            "Method not available.",
            written = true,
        ) {
            loadRefusal = "Method not available."
        }
    }

    /**
     * A Chrome set up by [setUp] in [scope] refuses a video and an audio viewer as unsupported,
     * naming [named], and loads no extension. Its files are [written] only when Chrome refused the
     * load.
     */
    private suspend fun assertRefused(
        scope: TestScope,
        named: String,
        written: Boolean = false,
        setUp: FakeChrome.() -> Unit,
    ) {
        withViewer(scope, setUp) { chrome, viewer ->
            val watches =
                listOf<suspend () -> Unit>({ viewer.watchVideo(SPEC) {} }, { viewer.watchAudio {} })
            for (watch in watches) {
                val refused = assertThrows<ApiException> { watch() }
                assertEquals(422, refused.status)
                assertEquals("unsupported", refused.error)
                assertTrue(refused.message.orEmpty().contains(named), refused.message)
            }
            assertEquals(0, chrome.loads)
            assertEquals(written, home.list().orEmpty().isNotEmpty())
        }
    }

    /**
     * A click from the input socket with nothing captured, as on a Chrome the viewer can't run on,
     * is dropped, and the socket goes on to its next contact rather than ending.
     */
    @Test
    fun `uncaptured click dropped`() = runTest {
        withViewer(this) { _, viewer ->
            val frames = Channel<PointerFrame>(Channel.UNLIMITED)
            frames.trySend(PointerFrame(PointerOp.DOWN, x = 10f, y = 10f))
            frames.trySend(PointerFrame(PointerOp.UP, x = 10f, y = 10f, tMs = 80))
            frames.close()
            // Throws, failing the test, if the click ends the socket's contacts.
            viewer.controls.contacts(frames)
            assertTrue(frames.tryReceive().isClosed, "The contacts after the click were not read")
        }
    }

    /** A viewer that joins a running stream gets nothing it cannot decode before a key frame. */
    @Test
    fun `joiner starts at a key frame`() = runTest {
        withViewer(this) { chrome, viewer ->
            val first = watch(viewer)
            chrome.encodes(300, 500)
            repeat(3) { chrome.frame() }
            runCurrent()
            assertEquals(listOf(KEY, DELTA, DELTA), first.types())
            val second = watch(viewer)
            // A frame the encoder made before it heard the second viewer's request for one.
            chrome.keyNext = false
            chrome.frame()
            runCurrent()
            repeat(2) { chrome.frame() }
            runCurrent()
            assertEquals(listOf(DELTA, KEY, DELTA), first.types())
            assertEquals(listOf(KEY, DELTA), second.types())
            assertEquals(300, second.header()?.get("width"))
        }
    }

    /**
     * A viewer whose socket stops draining, for 10 s of frames at 30 fps. It drops every key frame
     * it is sent, so asking for one each time would make every frame a key frame for every viewer.
     */
    @Test
    fun `stalled viewer keys are paced`() = runTest {
        withViewer(this) { chrome, viewer ->
            val reading = watch(viewer)
            watch(viewer)
            chrome.encodes(300, 500)
            var keys = 0
            val received = ArrayList<Int>()
            repeat(FRAMES) {
                if (chrome.frame() == KEY) keys++
                advanceTimeBy(FRAME_MS)
                runCurrent()
                received += reading.types()
            }
            // The first frame, then one when the stalled viewer first drops a frame, and again
            // 0.5, 1, 2, and 4 s after each ask, as on the phone.
            assertEquals(6, keys)
            assertEquals(FRAMES, received.size)
        }
    }

    /**
     * Another tab comes to the front, and the extension, which stops the tab it captured first,
     * fails to capture it. Viewers are closed with the reason rather than left with no frames, and
     * one that reconnects starts a capture again.
     */
    @Test
    fun `failed switch closes viewers`() = runTest {
        withViewer(this, { tabs["TAB2"] = "PAGE2" }) { chrome, viewer ->
            val watching = watch(viewer)
            browser.pages = listOf("PAGE", "PAGE2")
            chrome.active[FakeChrome.NORMAL] = "PAGE2"
            chrome.captureError = "Permission denied"
            chrome.activated()
            runCurrent()
            val failure = watching.failure.orEmpty()
            assertTrue(failure.contains("Permission denied"), failure)
            assertTrue(watching.headers.tryReceive().isClosed)
            chrome.captureError = null
            val captures = chrome.capture
            watch(viewer)
            assertEquals(captures + 1, chrome.capture)
        }
    }

    /**
     * The agent's input brings its tab forward as the first viewer connects, while the extension
     * loads: Chrome switches before the new worker's listeners are in place, so no news of it
     * comes, and the pages have not yet heard, so the one Chrome showed still reports itself
     * visible and the other hidden. Capture starts on the tab Chrome shows now. Chrome 156 did this
     * through the jar, with the switch 30 to 60 ms after the viewer connected.
     */
    @Test
    fun `switch at load is followed`() = runTest {
        val setUp: FakeChrome.() -> Unit = {
            tabs["TAB2"] = "PAGE2"
            onCommand = { method ->
                if (method == "Extensions.loadUnpacked") active[FakeChrome.NORMAL] = "PAGE2"
            }
        }
        withViewer(this, setUp) { chrome, viewer ->
            browser.pages = listOf("PAGE", "PAGE2")
            watch(viewer)
            assertEquals("TAB2", chrome.capturing)
        }
    }

    /**
     * The agent's input brings its tab forward while the first capture starts on the tab Chrome
     * showed. The news of the switch comes then, and is followed once that capture has started,
     * when the captured tab reports itself visible, as a captured tab does, and the other has not
     * yet heard it shows. Capture moves to the tab Chrome shows. Chrome 156 did this through the
     * jar: the tab brought forward still read hidden 150 ms after the news.
     */
    @Test
    fun `switch at capture is followed`() = runTest {
        val setUp: FakeChrome.() -> Unit = {
            tabs["TAB2"] = "PAGE2"
            var switched = false
            onCommand = { method ->
                if (method == "Extensions.triggerAction" && !switched) {
                    switched = true
                    active[FakeChrome.NORMAL] = "PAGE2"
                    activated()
                }
            }
        }
        withViewer(this, setUp) { chrome, viewer ->
            browser.pages = listOf("PAGE", "PAGE2")
            val watching = watch(viewer)
            runCurrent()
            assertEquals("TAB2", chrome.capturing)
            assertEquals(2, chrome.capture)
            assertNull(watching.failure)
        }
    }

    /**
     * A page opens a popup, which takes focus, and later a tab in another normal window takes it.
     * The extension's action on the popup's tab would crash Chrome, so capture stays on its tab,
     * then moves to the other normal window's tab, and stays there when the popup is focused again.
     */
    @Test
    fun `popup is passed over`() = runTest {
        val setUp: FakeChrome.() -> Unit = {
            tabs["POPUP_TAB"] = "POPUP"
            windowOf["POPUP"] = POPUP_WINDOW
            windows[POPUP_WINDOW] = "popup"
            tabs["TAB2"] = "PAGE2"
            windowOf["PAGE2"] = OTHER_WINDOW
            windows[OTHER_WINDOW] = "normal"
        }
        withViewer(this, setUp) { chrome, viewer ->
            val watching = watch(viewer)
            val first = chrome.capture
            browser.pages = listOf("PAGE", "POPUP")
            chrome.active[POPUP_WINDOW] = "POPUP"
            chrome.focus(POPUP_WINDOW)
            chrome.activated()
            runCurrent()
            assertFalse(chrome.crashed)
            assertEquals(first, chrome.capture)
            browser.pages = listOf("PAGE", "POPUP", "PAGE2")
            chrome.active[OTHER_WINDOW] = "PAGE2"
            chrome.focus(OTHER_WINDOW)
            chrome.activated()
            runCurrent()
            assertFalse(chrome.crashed)
            assertEquals(first + 1, chrome.capture)
            assertEquals("TAB2", chrome.capturing)
            chrome.focus(POPUP_WINDOW)
            chrome.activated()
            runCurrent()
            assertFalse(chrome.crashed)
            assertEquals("TAB2", chrome.capturing)
            assertNull(watching.failure)
        }
    }

    /**
     * A popup opens in front of the captured tab and takes focus, so the viewer's controls carry a
     * notice that says why the picture is another tab. It clears when the person focuses the
     * captured tab's window with the popup still open, and when the popup closes. A viewer that
     * starts capture while the popup is in front gets the notice too.
     */
    @Test
    fun `popup in front gives a notice`() = runTest {
        val setUp: FakeChrome.() -> Unit = {
            tabs["POPUP_TAB"] = "POPUP"
            windowOf["POPUP"] = POPUP_WINDOW
            windows[POPUP_WINDOW] = "popup"
            active[POPUP_WINDOW] = "POPUP"
        }
        withViewer(this, setUp) { chrome, viewer ->
            val notice = viewer.controls.notice
            val watching = backgroundScope.launch {
                viewer.watchVideo(SPEC) { awaitCancellation() }
            }
            runCurrent()
            assertNull(notice.value)
            browser.pages = listOf("PAGE", "POPUP")
            chrome.focus(POPUP_WINDOW)
            chrome.activated()
            runCurrent()
            assertTrue(notice.value.orEmpty().contains("popup"), notice.value)
            chrome.focus(FakeChrome.NORMAL)
            chrome.activated()
            runCurrent()
            assertNull(notice.value)
            chrome.focus(POPUP_WINDOW)
            chrome.activated()
            runCurrent()
            assertTrue(notice.value.orEmpty().contains("popup"), notice.value)
            browser.pages = listOf("PAGE")
            chrome.closeWindow(POPUP_WINDOW)
            chrome.activated()
            runCurrent()
            assertNull(notice.value)

            // The last viewer leaves, a popup opens in front again, and a viewer comes back.
            watching.cancel()
            runCurrent()
            browser.pages = listOf("PAGE", "POPUP")
            chrome.windows[POPUP_WINDOW] = "popup"
            chrome.active[POPUP_WINDOW] = "POPUP"
            chrome.focus(POPUP_WINDOW)
            watch(viewer)
            assertTrue(notice.value.orEmpty().contains("popup"), notice.value)
            assertFalse(chrome.crashed)
        }
    }

    /**
     * The captured tab closes, and only a popup shows. Viewers are closed with the reason, rather
     * than Chrome crashed by the action on the popup's tab.
     */
    @Test
    fun `popup alone closes viewers`() = runTest {
        val setUp: FakeChrome.() -> Unit = {
            tabs["POPUP_TAB"] = "POPUP"
            windowOf["POPUP"] = POPUP_WINDOW
            windows[POPUP_WINDOW] = "popup"
        }
        withViewer(this, setUp) { chrome, viewer ->
            val watching = watch(viewer)
            browser.pages = listOf("POPUP")
            chrome.active[POPUP_WINDOW] = "POPUP"
            chrome.focus(POPUP_WINDOW)
            chrome.closeWindow(FakeChrome.NORMAL)
            chrome.ended()
            runCurrent()
            assertFalse(chrome.crashed)
            val failure = watching.failure.orEmpty()
            assertTrue(failure.contains("popup"), failure)
        }
    }

    /**
     * The only tab showing is in a window of another profile, which has no action for the
     * extension, so a viewer is refused instead of Chrome crashed.
     */
    @Test
    fun `other profile's tab is refused`() = runTest {
        val setUp: FakeChrome.() -> Unit = {
            windowOf["PAGE"] = OTHER_WINDOW
            closeWindow(FakeChrome.NORMAL)
        }
        withViewer(this, setUp) { chrome, viewer ->
            assertThrows<ApiException> { viewer.watchVideo(SPEC) {} }
            assertFalse(chrome.crashed)
            assertEquals(0, chrome.capture)
        }
    }

    /**
     * Chrome names the extension's document while it is still loading, as Chrome 156 did right
     * after a new version of the extension loaded. The first viewer waits for the document's
     * script, rather than failing because there is no `fingertipsViewer` to call yet.
     */
    @Test
    fun `first viewer waits for script`() = runTest {
        withViewer(this, { scriptRan = false }) { chrome, viewer ->
            var joined: VideoViewer? = null
            var failure: ApiException? = null
            backgroundScope.launch {
                try {
                    viewer.watchVideo(SPEC) {
                        joined = it
                        awaitCancellation()
                    }
                } catch (e: ApiException) {
                    failure = e
                }
            }
            runCurrent()
            assertNull(failure?.message)
            assertNull(joined)
            assertEquals(0, chrome.capture)
            chrome.runScript()
            runCurrent()
            assertNull(failure?.message)
            assertNotNull(joined)
            assertEquals(1, chrome.capture)
        }
    }

    /**
     * A document whose script never runs fails the viewer at the deadline, and leaves nothing
     * behind: once it runs, the next viewer captures.
     */
    @Test
    fun `unstarted document times out`() = runTest {
        withViewer(this, { scriptRan = false }) { chrome, viewer ->
            val failure = assertThrows<ApiException> { viewer.watchVideo(SPEC) {} }
            assertTrue(failure.message.orEmpty().contains("did not start in time"), failure.message)
            assertEquals(0, chrome.capture)
            chrome.runScript()
            watch(viewer)
            assertEquals(1, chrome.capture)
        }
    }

    /** A call into the document that throws fails with what the script threw. */
    @Test
    fun `document's exception is named`() = runTest {
        withViewer(this, { throws = "TypeError: boom" }) { chrome, viewer ->
            val failure = assertThrows<ApiException> { viewer.watchVideo(SPEC) {} }
            assertTrue(failure.message.orEmpty().contains("TypeError: boom"), failure.message)
            assertEquals(0, chrome.capture)
        }
    }

    /** The extension's capture ended on its own, as when its tab closed: capture moves on. */
    @Test
    fun `ended capture recaptures`() = runTest {
        withViewer(this) { chrome, viewer ->
            val watching = watch(viewer)
            val ended = chrome.capture
            chrome.ended()
            runCurrent()
            assertEquals(ended + 1, chrome.capture)
            assertNull(watching.failure)
        }
    }

    @Test
    fun `Chrome quitting closes viewers`() = runTest {
        withViewer(this) { chrome, viewer ->
            val watching = watch(viewer)
            chrome.close()
            runCurrent()
            assertEquals("Chrome stopped", watching.failure)
            assertTrue(watching.headers.tryReceive().isClosed)
        }
    }

    /**
     * The worker reports a tab switch as Chrome quits, as it does when a window closes. Following
     * it would ask Chrome which tab shows, which starts a new Chrome.
     */
    @Test
    fun `quit news starts no Chrome`() = runTest {
        withViewer(this) { chrome, viewer ->
            val watching = watch(viewer)
            val listed = browser.listed
            chrome.activated()
            chrome.close()
            runCurrent()
            assertEquals(listed, browser.listed)
            assertEquals("Chrome stopped", watching.failure)
        }
    }

    /**
     * The window grows. A capture keeps the frame size it started at, so the tab is captured again
     * at its new size, and the header for that capture names the tab's new viewport, which clicks
     * are mapped to. A window that only moves changes nothing.
     */
    @Test
    fun `resize recaptures the tab`() = runTest {
        withViewer(this) { chrome, viewer ->
            val watching = watch(viewer)
            chrome.encodes(300, 500)
            runCurrent()
            assertEquals(600, watching.header()?.get("displayWidth"))
            val first = chrome.capture
            chrome.resized()
            runCurrent()
            assertEquals(first, chrome.capture)
            chrome.viewport = 800 to 1000
            chrome.resized()
            runCurrent()
            assertEquals(first + 1, chrome.capture)
            chrome.encodes(400, 500)
            runCurrent()
            val header = watching.header()
            assertEquals(400, header?.get("width"))
            assertEquals(800, header?.get("displayWidth"))
        }
    }

    /** A tab that reports no page holds up nothing: tabs are linked from Chrome's events. */
    @Test
    fun `silent tab holds up nothing`() = runTest {
        val tabs: FakeChrome.() -> Unit = {
            this.tabs.clear()
            this.tabs["SILENT"] = null
            this.tabs["TAB"] = "PAGE"
        }
        withViewer(this, tabs) { chrome, viewer ->
            watch(viewer)
            assertTrue(chrome.capture > 0)
            assertEquals(0L, testScheduler.currentTime)
        }
    }

    /**
     * Other versions of the extension are removed when the first viewer connects, and not again
     * when Chrome restarts, so the copy a server wrote is never removed while it runs.
     */
    @Test
    fun `old extensions pruned once`() = runTest {
        val stale = File(home, "viewer-extension-old").apply { mkdirs() }
        val other = File(home, "viewer-extension-other")
        FakeChrome().use { first ->
            FakeChrome().use { second ->
                val dispatcher = StandardTestDispatcher(testScheduler)
                CdpBrowserConnection(first, dispatcher).use { firstConnection ->
                    CdpBrowserConnection(second, dispatcher).use { secondConnection ->
                        firstConnection.start()
                        secondConnection.start()
                        var current = firstConnection
                        val viewer =
                            ChromeViewer(
                                connection = { current },
                                browser = browser,
                                extensionHome = home,
                                pointer = DesktopPointer(),
                                scope = backgroundScope,
                                now = { testScheduler.currentTime },
                            )
                        viewer.watchVideo(SPEC) {}
                        assertFalse(stale.exists())
                        other.mkdirs()
                        firstConnection.close()
                        runCurrent()
                        current = secondConnection
                        viewer.watchVideo(SPEC) {}
                        assertTrue(other.exists())
                        assertTrue(
                            second.evaluated.any { it.startsWith("fingertipsViewer.capture(") }
                        )
                    }
                }
            }
        }
    }

    /**
     * With no viewer, selecting a tab loads the extension and selects it there, which leaves the
     * app in front as it was, and sends none of DevTools' commands that bring Chrome in front.
     */
    @Test
    fun `select uses the extension`() = runTest {
        withViewer(this, { tabs["TAB2"] = "PAGE2" }) { chrome, viewer ->
            assertTrue(viewer.select("chrome~PAGE2"))
            assertEquals("PAGE2", chrome.active[FakeChrome.NORMAL])
            assertEquals(1, chrome.loads)
            assertTrue(viewer.select("chrome~PAGE"))
            assertEquals("PAGE", chrome.active[FakeChrome.NORMAL])
            assertEquals(1, chrome.loads)
            val sent = chrome.commands.map { it.first }
            assertFalse("Page.bringToFront" in sent, sent.toString())
            assertFalse("Target.activateTarget" in sent, sent.toString())
        }
    }

    /**
     * With no viewer, which tab Chrome shows comes from the extension, as the worker reads it from
     * Chrome, and follows a selection.
     */
    @Test
    fun `showing uses the extension`() = runTest {
        withViewer(this, { tabs["TAB2"] = "PAGE2" }) { chrome, viewer ->
            assertEquals("chrome~PAGE", viewer.showing())
            assertTrue(viewer.select("chrome~PAGE2"))
            assertEquals("chrome~PAGE2", viewer.showing())
            assertEquals(1, chrome.loads)
        }
    }

    /** A target id the extension finds no tab for is not selected, so the caller falls back. */
    @Test
    fun `select of no tab is false`() = runTest {
        withViewer(this) { chrome, viewer ->
            assertFalse(viewer.select("chrome~GONE"))
            assertEquals("PAGE", chrome.active[FakeChrome.NORMAL])
        }
    }

    /**
     * On Chrome 125, whose DevTools has no Extensions domain, and on Chrome 146 to 148, which
     * refuse the load, selecting a tab says it could not, loads nothing, and asks Chrome only once.
     */
    @Test
    fun `select without extension`() = runTest {
        val setUps =
            listOf<FakeChrome.() -> Unit>(
                { missing += setOf("Extensions.loadUnpacked", "Extensions.triggerAction") },
                { loadRefusal = "Method not available." },
            )
        for (setUp in setUps) {
            withViewer(this, setUp) { chrome, viewer ->
                assertFalse(viewer.select("chrome~PAGE"))
                val asked = chrome.commands.toList()
                assertFalse(viewer.select("chrome~PAGE"))
                assertNull(viewer.showing())
                assertEquals(asked, chrome.commands.toList())
                assertEquals(0, chrome.loads)
            }
        }
    }

    /** Runs [test] with a viewer of a [FakeChrome] set up by [setUp], on [scope]'s clock. */
    private suspend fun withViewer(
        scope: TestScope,
        setUp: FakeChrome.() -> Unit = {},
        test: suspend (FakeChrome, ChromeViewer) -> Unit,
    ) {
        FakeChrome().use { chrome ->
            chrome.setUp()
            val dispatcher = StandardTestDispatcher(scope.testScheduler)
            CdpBrowserConnection(chrome, dispatcher).use { connection ->
                connection.start()
                val viewer =
                    ChromeViewer(
                        connection = { connection },
                        browser = browser,
                        extensionHome = home,
                        pointer = DesktopPointer(),
                        scope = scope.backgroundScope,
                        now = { scope.testScheduler.currentTime },
                    )
                test(chrome, viewer)
            }
        }
    }

    /** A viewer that watches until the test ends, and does not read what it is sent. */
    private fun TestScope.watch(viewer: ChromeViewer): VideoViewer {
        var joined: VideoViewer? = null
        backgroundScope.launch {
            viewer.watchVideo(SPEC) {
                joined = it
                awaitCancellation()
            }
        }
        runCurrent()
        return checkNotNull(joined) { "The viewer did not join" }
    }

    /** The types of the frames queued for this viewer, which it reads. */
    private fun VideoViewer.types(): List<Int> = generateSequence {
        queue.frames.tryReceive().getOrNull()
    }
        .map { it[0].toInt() }
        .toList()

    /** The last header waiting for this viewer, with its numbers. */
    private fun VideoViewer.header(): Map<String, Int>? =
        headers.tryReceive().getOrNull()?.let { text ->
            Json.parseToJsonElement(text)
                .jsonObject
                .mapNotNull { (key, value) ->
                    runCatching { key to value.jsonPrimitive.int }.getOrNull()
                }
                .toMap()
        }

    private companion object {
        val SPEC = VideoSpec(1280, 30, 4_000_000)
        const val KEY = FakeChrome.KEY
        const val DELTA = FakeChrome.DELTA
        const val FRAMES = 300
        const val POPUP_WINDOW = 2
        const val OTHER_WINDOW = 3
        const val FRAME_MS = 33L
    }
}
