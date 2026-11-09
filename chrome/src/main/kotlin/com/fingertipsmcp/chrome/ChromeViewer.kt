package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpBrowserConnection
import com.fingertipsmcp.browser.DesktopPointer
import com.fingertipsmcp.browser.OpenCdpSession
import com.fingertipsmcp.browser.ViewerPageInput
import com.fingertipsmcp.browser.decodeCdp
import com.fingertipsmcp.browser.evalResult
import com.fingertipsmcp.browser.hasCommand
import com.fingertipsmcp.browser.pagePixelRatio
import com.fingertipsmcp.browser.parseBrowserTargetId
import com.fingertipsmcp.browser.refusedByChrome
import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.AudioHeader
import com.fingertipsmcp.model.ClipboardWriteRequest
import com.fingertipsmcp.model.KeyRequest
import com.fingertipsmcp.model.Point
import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.PointerOp
import com.fingertipsmcp.model.TextRequest
import com.fingertipsmcp.model.VideoHeader
import com.fingertipsmcp.model.ViewerHover
import com.fingertipsmcp.model.ViewerPointer
import com.fingertipsmcp.model.ViewerWheel
import com.fingertipsmcp.server.AudioFeed
import com.fingertipsmcp.server.MouseControls
import com.fingertipsmcp.server.VideoFeed
import com.fingertipsmcp.server.ViewerControls
import com.fingertipsmcp.server.ViewerCopy
import com.fingertipsmcp.stream.AudioQueue
import com.fingertipsmcp.stream.DEFAULT_BIT_RATE
import com.fingertipsmcp.stream.DEFAULT_FPS
import com.fingertipsmcp.stream.DEFAULT_MAX_SIZE
import com.fingertipsmcp.stream.FRAME_CONFIG
import com.fingertipsmcp.stream.VideoSpec
import com.fingertipsmcp.stream.VideoViewer
import com.fingertipsmcp.stream.VideoViewers
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * The viewer for a desktop Chrome: the tab Chrome shows, as H.264 video and Opus audio, and a
 * person's mouse and keyboard on it.
 *
 * A small extension, loaded when the first viewer connects, does the capture: FingertipsMCP runs
 * its action on the showing tab with `Extensions.triggerAction`, which lets `tabCapture` capture
 * that tab with no picker or banner, and its offscreen document encodes with WebCodecs and hands
 * frames back through a DevTools binding. Capture follows the tab Chrome shows, as the screen
 * holder or a person switches tabs, and stops when the last viewer leaves. Frames reach viewers as
 * the phone's do, through [VideoViewers].
 *
 * When the extension's capture stops and no other takes its place, or Chrome goes away, every
 * viewer is closed with the reason, as the phone's stream closes them when its pipeline fails, and
 * a viewer that reconnects starts a new capture.
 */
class ChromeViewer(
    private val connection: suspend () -> CdpBrowserConnection,
    private val browser: BrowserService,
    /**
     * The directory the extension's files are written under for Chrome to load. Only one Chrome
     * runs on a profile, so the profile's directory belongs to one server at a time.
     */
    private val extensionHome: File,
    private val scope: CoroutineScope,
    /** The browser's mouse and keyboard, which the viewer shares with the agent's input. */
    private val pointer: DesktopPointer,
    /** Monotonic milliseconds. */
    private val now: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    private val gate = Mutex()
    private val videoViewers = VideoViewers(::requestKeyFrame, now)
    private val audioViewers = CopyOnWriteArrayList<AudioQueue>()
    private var loaded: Loaded? = null
    /** The session on the offscreen document of [loaded]'s extension, once it is open. */
    @Volatile private var document: OpenCdpSession? = null
    /**
     * What the extension captures: set as it is asked to capture, and cleared once its capture
     * stops. It changes under [named], with [header], so a header always names one capture.
     */
    @Volatile private var captured: Captured? = null
    /** Captures started, so news from the extension says which one it is about. */
    private var captures = 0
    @Volatile private var spec: VideoSpec? = null
    /** The header for [captured]'s frames, for viewers that join; null before its first frame. */
    @Volatile private var header: String? = null
    private val named = Any()
    @Volatile private var audioConfig: ByteArray? = null
    private var input: PageInput? = null
    /** Whether other versions of the extension have been removed, which is done once. */
    private var pruned = false
    /** What [controls] tells viewer pages of the picture, set as capture follows the tabs. */
    private val notice = MutableStateFlow<String?>(null)

    /** The extension loaded into one browser connection, and what it knows of its targets. */
    private data class Loaded(
        val connection: CdpBrowserConnection,
        val id: String,
        val targets: ViewerTargets,
    )

    /**
     * The extension's capture [id] of [pageId], and the tab's viewport and the encoded picture's
     * size, which the header names. The picture is null until the first frame.
     */
    private data class Captured(
        val pageId: String,
        val id: Int,
        val display: Display,
        val picture: Picture? = null,
    )

    private data class Display(val width: Int, val height: Int, val pixelRatio: Float)

    private data class Picture(val width: Int, val height: Int)

    /** The page input for the captured tab at its pixel ratio, on a session of its own. */
    private data class PageInput(
        val pageId: String,
        val pixelRatio: Float,
        val session: OpenCdpSession,
        val input: ViewerPageInput,
    )

    val video = VideoFeed { session, spec -> watchVideo(spec) { viewer -> viewer.send(session) } }

    val audio = AudioFeed { session -> watchAudio { queue -> queue.send(session, AUDIO_HEADER) } }

    val controls: ViewerControls = Controls()

    /**
     * Adds a video viewer, starting capture for the first, and runs [serve] with it. The viewer is
     * counted from inside the `try`, so one whose start fails is removed as one that leaves is, and
     * capture stops once the last real viewer leaves.
     */
    internal suspend fun watchVideo(asked: VideoSpec, serve: suspend (VideoViewer) -> Unit) {
        val viewer = VideoViewer()
        try {
            gate.withLock {
                videoViewers.add(viewer)
                if (captured == null) {
                    spec = asked
                    start()
                } else {
                    // Read once the viewer is added, so a header sent meanwhile reaches it too.
                    header?.let(viewer.headers::trySend)
                    keyFrame()
                }
            }
            serve(viewer)
        } finally {
            withContext(NonCancellable) { leave { videoViewers.remove(viewer) } }
        }
    }

    /** Adds an audio viewer, as [watchVideo] does a video one, and runs [serve] with its queue. */
    internal suspend fun watchAudio(serve: suspend (AudioQueue) -> Unit) {
        val queue = AudioQueue(AUDIO_QUEUE)
        try {
            gate.withLock {
                val first = audioViewers.isEmpty()
                audioViewers += queue
                audioConfig?.let(queue::offer)
                if (captured == null) start()
                if (first) offscreen("fingertipsViewer.configure({audio: true})")
            }
            serve(queue)
        } finally {
            withContext(NonCancellable) {
                leave {
                    audioViewers -= queue
                    if (audioViewers.isEmpty() && captured != null) {
                        offscreen("fingertipsViewer.configure({audio: false})")
                    }
                }
            }
        }
    }

    /** Removes a viewer with [remove], and stops capturing when none is left. */
    private suspend fun leave(remove: suspend () -> Unit) {
        gate.withLock {
            remove()
            if (videoViewers.isEmpty() && audioViewers.isEmpty()) stop()
        }
    }

    /** Loads the extension if this browser lacks it, and captures the tab Chrome shows. */
    private suspend fun start() {
        val ext = load()
        capture(ext, showingTab(ext) ?: throw nothingCapturable())
        showFocus(ext)
    }

    @Suppress("MissingUseCall") // Loaded owns the target watch, which ends with its connection.
    private suspend fun load(): Loaded {
        val browserConnection = connection()
        loaded
            ?.takeIf { it.connection === browserConnection && browserConnection.isOpen }
            ?.let {
                return it
            }
        requireExtensionCommands(browserConnection)
        val dir = writeExtension()
        // Watching before the extension loads, so its document is seen however soon it opens.
        val targets = ViewerTargets.open(browserConnection, scope)
        var made: Loaded? = null
        try {
            val reply =
                try {
                    browserConnection.root.call(
                        "Extensions.loadUnpacked",
                        buildJsonObject { put("path", dir.absolutePath) },
                    )
                } catch (e: ApiException) {
                    // Chrome 146 to 148 have the command but refuse it over the pipe unless Chrome
                    // was started with --enable-unsafe-extension-debugging. Whatever the reason,
                    // a Chrome that won't load this extension can't run the viewer.
                    if (!e.refusedByChrome) throw e
                    throw unsupported(
                        "This Chrome would not load its extension: ${e.message.orEmpty()}",
                        e,
                    )
                }
            val id =
                reply.decodeCdp<LoadedExtension>()?.id
                    ?: throw ApiException.unavailable(
                        "capture_failed",
                        "Chrome did not load the viewer",
                    )
            log.info("Loaded the viewer extension {}", id)
            made = Loaded(browserConnection, id, targets)
        } finally {
            if (made == null) targets.close()
        }
        document = null
        loaded = made
        scope.launch { watch(made) }
        return made
    }

    /**
     * Refuses the viewer on a Chrome whose DevTools can't load the extension or run its action,
     * before anything is written or loaded. Chrome 125 has neither command, and
     * `Extensions.triggerAction` first came in Chrome 146, so Chrome 126 to 145 would load an
     * extension that can capture nothing.
     */
    private suspend fun requireExtensionCommands(connection: CdpBrowserConnection) {
        val missing = EXTENSION_COMMANDS.firstOrNull { !connection.root.hasCommand(it) } ?: return
        throw unsupported("This Chrome's DevTools has no $missing.")
    }

    /**
     * Ends the viewers' streams once the connection [ext] was loaded into closes, as when Chrome
     * quits, since no frame comes after that.
     */
    private suspend fun watch(ext: Loaded) {
        ext.connection.awaitClosed()
        gate.withLock {
            if (loaded !== ext) return@withLock
            loaded = null
            document = null
            if (captured != null || !videoViewers.isEmpty() || audioViewers.isNotEmpty()) {
                fail(CHROME_STOPPED)
            }
        }
    }

    /**
     * Writes the extension under a directory named by a hash of its files, and the first time,
     * removes copies of other versions. An unpacked extension's id comes from its path, and Chrome
     * keeps running the service worker it stored for that id in the profile, even after the files
     * and the manifest's version change, and it refuses to clear that. A changed extension needs a
     * path, and so an id, of its own.
     */
    private fun writeExtension(): File {
        val files = EXTENSION_FILES.associateWith { name ->
            checkNotNull(ChromeViewer::class.java.getResourceAsStream("viewer/$name")) {
                    "viewer/$name is missing from the jar"
                }
                .use { it.readBytes() }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { (name, bytes) ->
            digest.update(name.toByteArray())
            digest.update(bytes)
        }
        val version =
            digest.digest().take(HASH_BYTES).joinToString("") { "%02x".format(Locale.ROOT, it) }
        val dir = File(extensionHome, "$EXTENSION_PREFIX$version")
        if (!pruned) {
            extensionHome
                .listFiles()
                ?.filter { it.name.startsWith(EXTENSION_PREFIX) && it != dir }
                ?.forEach { it.deleteRecursively() }
            pruned = true
        }
        dir.mkdirs()
        files.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        return dir
    }

    /**
     * The tab Chrome shows that the viewer can capture: the active tab of the normal window Chrome
     * focused last, as the extension's worker reads it from Chrome. It is read from Chrome rather
     * than from which page reports itself visible, since a page hears of a switch only some time
     * after Chrome makes it, by when the news of the switch may have come and gone, and a captured
     * tab reports itself visible whatever Chrome shows. Only a normal window has the extension's
     * action: a popup's has no toolbar, and a window of another profile, which the extension does
     * not see, has no action for it, nor has a window `--app` opens; an installed web app's window
     * has one, but the extension reads both as type `app`, so both are passed over. Chrome 156 runs
     * `Extensions.triggerAction` on a tab in such a window without checking, and crashes;
     * Chromium's main branch checks and refuses. With no normal window open, the captured tab
     * stays, if it is still open.
     */
    @Suppress("MissingUseCall") // The document's session stays open until capture stops.
    private suspend fun showingTab(ext: Loaded): String? {
        val failed = "The viewer extension did not say which tab Chrome shows"
        val reply =
            offscreenSession(ext)
                .evaluate("fingertipsViewer.showing()", failed)
                .decodeCdp<ShowingTab>()
        val ids =
            reply?.ids
                ?: throw ApiException.unavailable(
                    "capture_failed",
                    "$failed: ${reply?.error ?: "no answer"}",
                )
        val pages = browser.targets().filter { it.type == "page" }.map { it.id }
        return pages.firstOrNull { parseBrowserTargetId(it).second in ids }
            ?: captured?.pageId?.takeIf { it in pages }
    }

    /**
     * The `chrome.windows` type of the window Chrome focused last, as the extension's worker gives
     * it. Null when Chrome has no window.
     */
    @Suppress("MissingUseCall") // The document's session stays open until capture stops.
    private suspend fun windowType(ext: Loaded): String? {
        val failed = "The viewer extension did not say what type the window is"
        val reply =
            offscreenSession(ext)
                .evaluate("fingertipsViewer.window()", failed)
                .decodeCdp<WindowType>()
        if (reply == null || reply.error != null) {
            throw ApiException.unavailable(
                "capture_failed",
                "$failed: ${reply?.error ?: "no answer"}",
            )
        }
        return reply.type
    }

    /**
     * Sets [notice] from the window Chrome focused last. When that is a popup, the person sees it
     * in front while the viewer shows another tab, so viewer pages are told why. The worker asks
     * `chrome.windows` with its default filter, which counts only normal and popup windows. Unknown
     * leaves the notice as it was.
     */
    private suspend fun showFocus(ext: Loaded) {
        val type =
            try {
                windowType(ext)
            } catch (e: ApiException) {
                log.debug("Could not read which window Chrome focused: {}", e.message)
                return
            }
        notice.value = if (type == null || type == NORMAL_WINDOW) null else FOCUS_NOTICE
    }

    /**
     * Captures [pageId]. The extension stops what it captured before it starts on another tab, so
     * once it is asked, [captured] names this capture, and it is cleared if the capture fails. A
     * failure before that leaves the capture running and [captured] as it was, unless the tab is
     * the one captured, whose capture stops before the action runs. [pageId] is one [showingTab]
     * chose, or the one captured, so its window has the extension's action, which the action needs
     * so as not to crash Chrome; Chrome moves no tab into a popup. Chrome 156 runs the action on
     * the active tab of [pageId]'s window, which is [pageId] unless Chrome switched tabs since
     * [showingTab] read it, and the news of that switch then moves capture on.
     */
    @Suppress("MissingUseCall") // The document's session stays open until capture stops.
    private suspend fun capture(ext: Loaded, pageId: String) {
        val (_, chromeId) = parseBrowserTargetId(pageId)
        val tab = ext.targets.tabOf(chromeId)
        // The worker opens this document once its listeners are in place, so the action below
        // reaches them.
        val document = offscreenSession(ext)
        if (captured?.pageId == pageId) {
            // Chrome gives no stream id for a tab it is capturing, so that capture stops first.
            offscreen("fingertipsViewer.stop()")
            name(null)
        }
        ext.connection.root.call(
            "Extensions.triggerAction",
            buildJsonObject {
                put("id", ext.id)
                put("targetId", tab)
            },
        )
        val display = display(pageId)
        val asked = spec ?: DEFAULT_SPEC
        val size = captureSize(display.width, display.height, display.pixelRatio, asked.maxSize)
        val id = ++captures
        val options = buildJsonObject {
            put("id", id)
            put("width", size.first)
            put("height", size.second)
            put("fps", asked.fps)
            put("bitRate", asked.bitRate)
            put("audio", audioViewers.isNotEmpty())
        }
        name(Captured(pageId, id, display))
        var started = false
        try {
            val failed = "Chrome did not capture the tab"
            val outcome =
                document
                    .evaluate("fingertipsViewer.capture($options)", failed)
                    .decodeCdp<CaptureOutcome>()
            if (outcome?.ok != true) {
                throw ApiException.unavailable(
                    "capture_failed",
                    "$failed: ${outcome?.error ?: "no answer"}",
                )
            }
            started = true
        } finally {
            if (!started) name(null)
        }
        log.info("Capturing {}", pageId)
    }

    /** Records [next] as what the extension captures, which has no header until its first frame. */
    private fun name(next: Captured?) {
        synchronized(named) {
            captured = next
            header = null
        }
    }

    /**
     * The offscreen document's session, with the binding frames come back through, once the
     * document's script has run. Chrome names the document's URL while it is still loading, before
     * `offscreen.js` has made `fingertipsViewer`, so a call sent then finds nothing to call.
     */
    @Suppress("MissingUseCall") // Closed when the capture stops or the browser goes.
    private suspend fun offscreenSession(ext: Loaded): OpenCdpSession {
        document
            ?.takeIf { loaded === ext }
            ?.let {
                return it
            }
        val url = "chrome-extension://${ext.id}/offscreen.html"
        val target =
            ext.targets.document(url)
                ?: throw ApiException.unavailable(
                    "capture_failed",
                    "The viewer extension's document did not open",
                )
        val session = ext.connection.attach(target)
        session.listen { event ->
            if (event.method != "Runtime.bindingCalled") return@listen
            val called = event.params.decodeCdp<BindingCalled>() ?: return@listen
            if (called.name == BINDING) received(called.payload)
        }
        var ready = false
        try {
            session.call("Runtime.addBinding", buildJsonObject { put("name", BINDING) })
            awaitScript(session)
            ready = true
        } finally {
            if (!ready) session.close()
        }
        document = session
        return session
    }

    /**
     * Returns once the offscreen document's script has run, which it does before the document's
     * `DOMContentLoaded`, since it is a classic script in the document's head. A document that
     * finished loading without making `fingertipsViewer`, or that is still loading at the deadline,
     * fails.
     */
    private suspend fun awaitScript(session: OpenCdpSession) {
        val failed = "The viewer extension's document did not start"
        val started =
            withTimeoutOrNull(DOCUMENT_READY_MS) { session.evaluate(DOCUMENT_READY, failed) }
                ?: throw ApiException.unavailable("capture_failed", "$failed in time")
        if (started.decodeCdp<Boolean>() != true) {
            throw ApiException.unavailable("capture_failed", "$failed: its script did not finish")
        }
    }

    /**
     * The value [expression] gives in the offscreen document on this session. When the script
     * throws, or the reply holds no value, it fails with [failed] and what went wrong.
     */
    private suspend fun OpenCdpSession.evaluate(expression: String, failed: String): JsonElement {
        val result = evalResult(call("Runtime.evaluate", evaluation(expression)))
        return result.value
            ?: throw ApiException.unavailable(
                "capture_failed",
                "$failed: ${result.exception ?: "no answer"}",
            )
    }

    /**
     * One message from the offscreen document. It runs on the connection's reader, so it waits for
     * nothing, and it is the one thread frames reach [videoViewers] on.
     */
    private fun received(payload: String) {
        if (payload.isEmpty()) return
        val body = payload.substring(1)
        when (payload[0]) {
            'V' -> videoViewers.emitFrame(Base64.getDecoder().decode(body))
            'A' -> {
                val bytes = Base64.getDecoder().decode(body)
                if (bytes[0].toInt() == FRAME_CONFIG) audioConfig = bytes
                for (viewer in audioViewers) viewer.offer(bytes)
            }
            'S' -> news(body.decodeNews())
        }
    }

    private fun String.decodeNews(): News? =
        try {
            ApiJson.decodeFromString(News.serializer(), this)
        } catch (_: SerializationException) {
            null
        }

    private fun news(news: News?) {
        when (news?.kind) {
            "video" -> pictured(news.capture, Picture(news.width, news.height))
            "activated" -> scope.launch { follow(ended = null) }
            "resized" -> scope.launch { resized() }
            "ended" -> scope.launch { follow(ended = news.capture) }
            "failed" -> scope.launch { failed(news.capture, news.detail) }
            "error" -> log.warn("The viewer's capture reported: {}", news.detail)
        }
    }

    /**
     * The capture [id] encodes at [picture]'s size from its next frame on. Viewers get the header
     * before that frame, since this runs on the reader that brings it, and frames queued at the old
     * size are dropped.
     */
    private fun pictured(id: Int, picture: Picture) {
        synchronized(named) {
            val shown = captured?.takeIf { it.id == id }?.copy(picture = picture) ?: return
            captured = shown
            val json = headerFor(shown) ?: return
            header = json
            videoViewers.emitHeader(json)
        }
    }

    private fun headerFor(shown: Captured): String? {
        val picture = shown.picture ?: return null
        val asked = spec ?: DEFAULT_SPEC
        return ApiJson.encodeToString(
            VideoHeader.serializer(),
            VideoHeader(
                "avc",
                picture.width,
                picture.height,
                asked.fps,
                asked.bitRate,
                shown.display.width,
                shown.display.height,
                ViewerPointer.MOUSE,
            ),
        )
    }

    /**
     * Captures the tab again when a window changed size and the captured tab's viewport with it. A
     * capture keeps the frame size it started at, so the picture would no longer match the tab, and
     * points mapped to the old viewport would land in the wrong place. The new capture is measured,
     * sized, and named to viewers as the first was.
     */
    private suspend fun resized() {
        gate.withLock {
            val ext = loaded?.takeIf { it.connection.isOpen } ?: return
            val running = captured ?: return
            try {
                if (display(running.pageId) == running.display) return
                capture(ext, running.pageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (captured == null) {
                    fail("Could not capture the tab at its new size: ${e.message ?: e.error}")
                } else {
                    log.warn("Could not measure the captured tab: {}", e.message)
                }
            }
        }
    }

    /**
     * Captures the tab Chrome shows now: when that is another tab, or after the capture [ended]
     * stopped on its own, as when its tab closed. When the extension is left capturing nothing,
     * viewers are closed. Each time, [notice] is set from the window Chrome focused.
     */
    private suspend fun follow(ended: Int?) {
        gate.withLock {
            // News that came as Chrome quit: asking which tab shows would start Chrome again,
            // and [watch] closes the viewers.
            val ext = loaded?.takeIf { it.connection.isOpen } ?: return
            val running = captured ?: return
            if (ended != null) {
                if (running.id != ended) return
                name(null)
            }
            try {
                val shown = showingTab(ext) ?: throw nothingCapturable()
                if (shown != captured?.pageId) capture(ext, shown)
                showFocus(ext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (captured == null) {
                    fail("Could not capture the tab Chrome shows: ${e.message ?: e.error}")
                } else {
                    log.warn("Could not follow the viewer to the tab Chrome shows: {}", e.message)
                }
            }
        }
    }

    /** The extension's capture [id] stopped, as when its video encoder failed. */
    private suspend fun failed(id: Int, detail: String?) {
        gate.withLock {
            if (captured?.id != id) return
            fail("The tab's capture stopped: ${detail ?: "no reason given"}")
        }
    }

    /**
     * Stops capturing and closes every viewer with [reason], so each reconnects and starts a
     * capture again, as the phone's stream does when its pipeline fails.
     */
    private suspend fun fail(reason: String) {
        log.warn("Closing the viewers: {}", reason)
        stop()
        videoViewers.close(reason)
        for (viewer in audioViewers) viewer.close()
    }

    private suspend fun keyFrame() {
        offscreen("fingertipsViewer.keyFrame()")
    }

    private fun requestKeyFrame() {
        scope.launch { keyFrame() }
    }

    /** Runs [expression] in the offscreen document, when there is one. */
    private suspend fun offscreen(expression: String) {
        val session = document ?: return
        try {
            session.call("Runtime.evaluate", evaluation(expression))
        } catch (e: ApiException) {
            log.debug("The viewer's document did not answer: {}", e.message)
        }
    }

    private suspend fun stop() {
        offscreen("fingertipsViewer.stop()")
        name(null)
        notice.value = null
        audioConfig = null
        input?.session?.close()
        input = null
        log.info("Stopped capturing")
    }

    /** The captured tab's viewport in CSS pixels and its pixel ratio. */
    private suspend fun display(pageId: String): Display {
        val (_, chromeId) = parseBrowserTargetId(pageId)
        val loadedNow = loaded ?: throw nothingShown()
        return loadedNow.connection.attach(chromeId).use { page ->
            val css = page.call("Page.getLayoutMetrics").decodeCdp<Metrics>()?.cssVisualViewport
            Display(
                css?.clientWidth?.roundToInt() ?: 0,
                css?.clientHeight?.roundToInt() ?: 0,
                pagePixelRatio(page),
            )
        }
    }

    /** The page input for the captured tab, opened when it changes. */
    @Suppress("MissingUseCall") // Closed when capture stops or moves to another tab.
    private suspend fun pageInput(): ViewerPageInput {
        val shown = captured ?: throw nothingShown()
        input
            ?.takeIf { it.pageId == shown.pageId && it.pixelRatio == shown.display.pixelRatio }
            ?.let {
                return it.input
            }
        input?.session?.close()
        val ext = loaded ?: throw nothingShown()
        val (_, chromeId) = parseBrowserTargetId(shown.pageId)
        val session = ext.connection.attach(chromeId)
        val made =
            PageInput(
                shown.pageId,
                shown.display.pixelRatio,
                session,
                ViewerPageInput(session, shown.display.pixelRatio, pointer, now),
            )
        input = made
        return made.input
    }

    /** The viewer's mouse and keyboard, on the captured tab. */
    private inner class Controls : MouseControls {
        override val notice: StateFlow<String?> = this@ChromeViewer.notice.asStateFlow()

        override suspend fun contacts(frames: ReceiveChannel<PointerFrame>) {
            var down: Point? = null
            try {
                for (frame in frames) {
                    if (frame.op != PointerOp.CANCEL && frame.id != 0) continue
                    try {
                        down = contact(frame.op, Point(frame.x, frame.y), down)
                    } catch (e: ApiException) {
                        // One contact that can't reach a tab, as with nothing captured, is
                        // dropped, and the socket stays open for the next. A press that failed
                        // left no button down, so its gesture's moves and release are dropped too.
                        log.debug("Dropped the viewer's {}: {}", frame.op, e.message)
                    }
                }
            } finally {
                val left = down
                if (left != null) {
                    try {
                        // A button left down would stay down in the page for good.
                        withContext(NonCancellable) { gate.withLock { pageInput() }.release(left) }
                    } catch (e: ApiException) {
                        log.debug("Could not release the viewer's button: {}", e.message)
                    }
                }
            }
        }

        /**
         * Sends one contact [op] at [at] to the captured tab, with the button down at [down], and
         * returns where the button is down after it, or null when it is up.
         */
        private suspend fun contact(op: PointerOp, at: Point, down: Point?): Point? =
            when (op) {
                PointerOp.DOWN -> {
                    gate.withLock { pageInput() }.press(at)
                    at
                }
                PointerOp.MOVE -> {
                    if (down != null) gate.withLock { pageInput() }.move(at, pressed = true)
                    down?.let { at }
                }
                PointerOp.UP -> {
                    if (down != null) gate.withLock { pageInput() }.release(at)
                    null
                }
                PointerOp.CANCEL -> {
                    down?.let { gate.withLock { pageInput() }.release(it) }
                    null
                }
            }

        override suspend fun key(request: KeyRequest): ActionResult =
            gate.withLock { pageInput() }.key(request.key, request.metaState)

        override suspend fun text(request: TextRequest): ActionResult =
            gate.withLock { pageInput() }.type(request.text)

        override suspend fun paste(request: ClipboardWriteRequest): ActionResult =
            gate.withLock { pageInput() }.insert(request.text)

        override suspend fun copy(): ViewerCopy {
            val selected = gate.withLock { pageInput() }.selection()
            return ViewerCopy(selected, null, changed = selected != null)
        }

        override suspend fun hover(at: ViewerHover) =
            gate.withLock { pageInput() }.move(Point(at.x, at.y), pressed = false)

        override suspend fun wheel(turn: ViewerWheel) =
            gate.withLock { pageInput() }.wheel(Point(turn.x, turn.y), turn.dx, turn.dy)
    }

    @Serializable private data class LoadedExtension(val id: String? = null)

    @Serializable private data class BindingCalled(val name: String, val payload: String)

    @Serializable
    private data class CaptureOutcome(val ok: Boolean? = null, val error: String? = null)

    /** The DevTools target ids of the tab Chrome shows, empty when it has no normal window. */
    @Serializable
    private data class ShowingTab(val ids: List<String>? = null, val error: String? = null)

    /** A window's type as `chrome.windows` names it, null when the extension can't see it. */
    @Serializable private data class WindowType(val type: String? = null, val error: String? = null)

    /** News from the offscreen document, with the capture it is about when it is about one. */
    @Serializable
    private data class News(
        val kind: String,
        val capture: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val detail: String? = null,
    )

    @Serializable private data class Metrics(val cssVisualViewport: Viewport? = null)

    @Serializable
    private data class Viewport(val clientWidth: Double = 0.0, val clientHeight: Double = 0.0)

    private companion object {
        const val BINDING = "fingertipsViewerSend"
        const val EXTENSION_PREFIX = "viewer-extension-"
        const val HASH_BYTES = 6
        val EXTENSION_FILES = listOf("manifest.json", "worker.js", "offscreen.html", "offscreen.js")
        /** The DevTools commands the viewer loads its extension and starts capture with. */
        val EXTENSION_COMMANDS = listOf("Extensions.loadUnpacked", "Extensions.triggerAction")
        val AUDIO_HEADER: String =
            ApiJson.encodeToString(AudioHeader.serializer(), AudioHeader("opus", 48_000, 2))
        /** Audio packets a viewer may fall behind by before the oldest go, as on the phone. */
        const val AUDIO_QUEUE = 8
        val DEFAULT_SPEC = VideoSpec(DEFAULT_MAX_SIZE, DEFAULT_FPS, DEFAULT_BIT_RATE)
        const val CHROME_STOPPED = "Chrome stopped"
        /** The `chrome.windows` type of a window with a toolbar, and so the extension's action. */
        const val NORMAL_WINDOW = "normal"
        /** The notice while Chrome's window in front is a popup. */
        const val FOCUS_NOTICE =
            "Chrome has a popup in front, which the viewer can't capture, so it shows another tab."
        const val NANOS_PER_MS = 1_000_000L
        /** How long the offscreen document's script has to run once Chrome names the document. */
        const val DOCUMENT_READY_MS = 5_000L
    }
}

/**
 * Resolves to whether the offscreen document's script made `fingertipsViewer`, once the script has
 * run: at once when the document has parsed, or at its `DOMContentLoaded`.
 */
internal const val DOCUMENT_READY =
    "new Promise((resolve) => {" +
        " const ready = () => resolve(typeof globalThis.fingertipsViewer === 'object');" +
        " if (document.readyState !== 'loading') ready();" +
        " else document.addEventListener('DOMContentLoaded', ready, { once: true });" +
        " })"

/**
 * The size to capture a tab [cssWidth] by [cssHeight] CSS pixels at: half its device pixels, as the
 * phone's stream is at half the screen, and no more than [maxSize] on its longer side, in even
 * numbers for H.264.
 */
internal fun captureSize(
    cssWidth: Int,
    cssHeight: Int,
    pixelRatio: Float,
    maxSize: Int,
): Pair<Int, Int> {
    val width = cssWidth * pixelRatio / 2
    val height = cssHeight * pixelRatio / 2
    val fit = minOf(1f, maxSize / max(width, height).coerceAtLeast(1f))
    fun even(value: Float) = (value * fit).toInt().coerceAtLeast(2) and 1.inv()
    return even(width) to even(height)
}

private fun evaluation(expression: String) = buildJsonObject {
    put("expression", expression)
    put("awaitPromise", true)
    put("returnByValue", true)
}

/** The viewer can't run on this Chrome, for [reason]. */
private fun unsupported(reason: String, cause: Throwable? = null) =
    ApiException(
        422,
        "unsupported",
        "The viewer needs Chrome $VIEWER_CHROME or later. $reason",
        cause = cause,
    )

/** The oldest Chrome that loads the viewer's extension over the pipe and runs its action. */
private const val VIEWER_CHROME = 149

private fun nothingShown() =
    ApiException.unavailable("capture_failed", "Chrome is showing no tab for the viewer.")

/** No tab shows that [ChromeViewer] can capture, which a tab in a popup window alone is not. */
private fun nothingCapturable() =
    ApiException.unavailable(
        "capture_failed",
        "Chrome is showing no tab the viewer can capture. It can't capture a popup window's tab.",
    )

private val log = LoggerFactory.getLogger("FingertipsChrome.Viewer")
