package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpBrowserConnection
import com.fingertipsmcp.browser.DesktopPointer
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.stream.VideoSpec
import com.fingertipsmcp.stream.VideoViewer
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent runs what is due on the virtual clock.
class ChromeViewerTest {
    @TempDir lateinit var home: File

    private val browser = ShowingBrowser(listOf("PAGE"), "PAGE")

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
                        browser = ShowingBrowser(listOf("PAGE"), "PAGE"),
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
            browser.shown = "PAGE2"
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
        const val FRAME_MS = 33L
    }
}
