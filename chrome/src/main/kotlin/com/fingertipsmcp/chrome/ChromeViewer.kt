package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpBrowserConnection
import com.fingertipsmcp.browser.OpenCdpSession
import com.fingertipsmcp.browser.ViewerPageInput
import com.fingertipsmcp.browser.decodeCdp
import com.fingertipsmcp.browser.pagePixelRatio
import com.fingertipsmcp.browser.parseBrowserTargetId
import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.AudioHeader
import com.fingertipsmcp.model.ClipboardWriteRequest
import com.fingertipsmcp.model.KeyRequest
import com.fingertipsmcp.model.OrientationRequest
import com.fingertipsmcp.model.Point
import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.PointerOp
import com.fingertipsmcp.model.TextRequest
import com.fingertipsmcp.model.VideoHeader
import com.fingertipsmcp.model.ViewerHover
import com.fingertipsmcp.model.ViewerPointer
import com.fingertipsmcp.model.ViewerWheel
import com.fingertipsmcp.server.AudioFeed
import com.fingertipsmcp.server.VideoFeed
import com.fingertipsmcp.server.ViewerControls
import com.fingertipsmcp.server.ViewerCopy
import com.fingertipsmcp.stream.VideoSpec
import com.fingertipsmcp.stream.sendUntilClientLeaves
import io.ktor.server.websocket.DefaultWebSocketServerSession
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
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
 * holder or a person switches tabs, and stops when the last viewer leaves. A viewer that falls
 * behind skips frames until the next key frame, which it asks for.
 */
class ChromeViewer(
    private val connection: suspend () -> CdpBrowserConnection,
    private val browser: BrowserService,
    /** The directory the extension's files are written under for Chrome to load. */
    private val extensionHome: File,
    private val scope: CoroutineScope,
    /** Monotonic milliseconds. */
    private val now: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    private val gate = Mutex()
    private val videoViewers = LinkedHashSet<ViewerQueue>()
    private val audioViewers = LinkedHashSet<ViewerQueue>()
    private var loaded: Loaded? = null
    /** The session on the offscreen document of [loaded]'s extension, once it is open. */
    @Volatile private var document: OpenCdpSession? = null
    /** The browser target id of the captured tab, and its tab target. */
    @Volatile private var captured: Captured? = null
    @Volatile private var spec: VideoSpec? = null
    @Volatile private var header: String? = null
    @Volatile private var audioConfig: ByteArray? = null
    private var input: PageInput? = null

    /** The extension loaded into one browser connection, and the tab targets of pages seen. */
    private data class Loaded(
        val connection: CdpBrowserConnection,
        val id: String,
        val tabTargets: MutableMap<String, String> = HashMap(),
    )

    private data class Captured(val pageId: String, val display: Display)

    private data class Display(val width: Int, val height: Int, val pixelRatio: Float)

    /** The page input for the captured tab, on a session of its own. */
    private data class PageInput(
        val pageId: String,
        val session: OpenCdpSession,
        val input: ViewerPageInput,
    )

    val video = VideoFeed { session, spec -> serveVideo(session, spec) }

    val audio = AudioFeed { session -> serveAudio(session) }

    val controls: ViewerControls = Controls()

    private suspend fun serveVideo(session: DefaultWebSocketServerSession, asked: VideoSpec) {
        val viewer = ViewerQueue { requestKeyFrame() }
        gate.withLock {
            videoViewers += viewer
            if (captured == null) {
                spec = asked
                start()
            } else {
                header?.let(viewer::text)
                keyFrame()
            }
        }
        try {
            sendUntilClientLeaves(session) {
                for (frame in viewer.out) session.outgoing.send(frame)
            }
        } finally {
            withContext(NonCancellable) { leave { videoViewers -= viewer } }
        }
    }

    private suspend fun serveAudio(session: DefaultWebSocketServerSession) {
        val viewer = ViewerQueue {}
        gate.withLock {
            val first = audioViewers.isEmpty()
            audioViewers += viewer
            viewer.text(ApiJson.encodeToString(AudioHeader.serializer(), AUDIO_HEADER))
            audioConfig?.let(viewer::offer)
            if (captured == null) start()
            if (first) offscreen("fingertipsViewer.configure({audio: true})")
        }
        try {
            sendUntilClientLeaves(session) {
                for (frame in viewer.out) session.outgoing.send(frame)
            }
        } finally {
            withContext(NonCancellable) {
                leave {
                    audioViewers -= viewer
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
        capture(ext, showingTab() ?: throw nothingShown())
    }

    private suspend fun load(): Loaded {
        val browserConnection = connection()
        loaded
            ?.takeIf { it.connection === browserConnection && browserConnection.isOpen }
            ?.let {
                return it
            }
        val dir = writeExtension()
        val reply =
            browserConnection.root.call(
                "Extensions.loadUnpacked",
                buildJsonObject { put("path", dir.absolutePath) },
            )
        val id =
            reply.decodeCdp<LoadedExtension>()?.id
                ?: throw ApiException.unavailable(
                    "capture_failed",
                    "Chrome did not load the viewer",
                )
        log.info("Loaded the viewer extension {}", id)
        document = null
        return Loaded(browserConnection, id).also { loaded = it }
    }

    /**
     * Writes the extension under a directory named by a hash of its files, and removes copies of
     * other versions. An unpacked extension's id comes from its path, and Chrome keeps running the
     * service worker it stored for that id in the profile, even after the files and the manifest's
     * version change, and it refuses to clear that. A changed extension needs a path, and so an id,
     * of its own.
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
        extensionHome
            .listFiles()
            ?.filter { it.name.startsWith(EXTENSION_PREFIX) && it != dir }
            ?.forEach {
                it.deleteRecursively()
            }
        dir.mkdirs()
        files.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        return dir
    }

    /**
     * The tab Chrome shows. A captured tab reports itself visible whatever Chrome shows, so while
     * one is captured, another tab that is visible is the one Chrome shows now, and the captured
     * tab stays when there is none.
     */
    private suspend fun showingTab(): String? {
        val pages = browser.targets().filter { it.type == "page" }.map { it.id }
        val now = captured?.pageId?.takeIf { it in pages }
        return pages.firstOrNull { it != now && browser.showing(it) == true } ?: now
    }

    @Suppress("MissingUseCall") // The document's session stays open until capture stops.
    private suspend fun capture(ext: Loaded, pageId: String) {
        val (_, chromeId) = parseBrowserTargetId(pageId)
        val tab = tabTarget(ext, chromeId)
        // The worker opens this document once its listeners are in place, so the action below
        // reaches them.
        val document = offscreenSession(ext)
        ext.connection.root.call(
            "Extensions.triggerAction",
            buildJsonObject {
                put("id", ext.id)
                put("targetId", tab)
            },
        )
        val display = display(pageId)
        val asked = spec ?: VideoSpec(DEFAULT_SIZE, DEFAULT_FPS, DEFAULT_BIT_RATE)
        val size = captureSize(display.width, display.height, display.pixelRatio, asked.maxSize)
        val options = buildJsonObject {
            put("width", size.first)
            put("height", size.second)
            put("fps", asked.fps)
            put("bitRate", asked.bitRate)
            put("audio", audioViewers.isNotEmpty())
        }
        val result =
            document.call("Runtime.evaluate", evaluation("fingertipsViewer.capture($options)"))
        val outcome = result.decodeCdp<EvalReply>()?.result?.value?.decodeCdp<CaptureOutcome>()
        if (outcome?.ok != true) {
            throw ApiException.unavailable(
                "capture_failed",
                "Chrome did not capture the tab: ${outcome?.error ?: "no answer"}",
            )
        }
        captured = Captured(pageId, display)
        header = null
        log.info("Capturing {}", pageId)
    }

    /**
     * The tab target that holds the page [chromeId]. `Extensions.triggerAction` takes a tab target,
     * which DevTools lists apart from its page with nothing linking them. A tab session with
     * auto-attach on reports the one page in it, which links them.
     */
    private suspend fun tabTarget(ext: Loaded, chromeId: String): String {
        ext.tabTargets[chromeId]?.let {
            return it
        }
        val root = ext.connection.root
        val tabs =
            root
                .call(
                    "Target.getTargets",
                    buildJsonObject {
                        put(
                            "filter",
                            buildJsonArray { add(buildJsonObject { put("type", "tab") }) },
                        )
                    },
                )
                .decodeCdp<TargetList>()
                ?.targetInfos
                .orEmpty()
        for (tab in tabs) {
            val page = pageIn(ext.connection, tab.targetId) ?: continue
            ext.tabTargets[page] = tab.targetId
            if (page == chromeId) return tab.targetId
        }
        throw ApiException.notFound("No tab holds the page $chromeId")
    }

    /** The page in the tab target [tabId], or null when Chrome did not say. */
    private suspend fun pageIn(browserConnection: CdpBrowserConnection, tabId: String): String? =
        browserConnection.attach(tabId).use { tab ->
            val child = CompletableDeferred<Attached>()
            tab.listen { event ->
                    if (event.method == "Target.attachedToTarget") {
                        event.params.decodeCdp<Attached>()?.let(child::complete)
                    }
                }
                .use {
                    tab.call(
                        "Target.setAutoAttach",
                        buildJsonObject {
                            put("autoAttach", true)
                            put("waitForDebuggerOnStart", false)
                            put("flatten", true)
                        },
                    )
                    val attached = withTimeoutOrNull(ATTACH_WAIT_MS) { child.await() }
                    attached?.sessionId?.let { child ->
                        browserConnection.root.post(
                            "Target.detachFromTarget",
                            buildJsonObject { put("sessionId", child) },
                        )
                    }
                    attached?.targetInfo?.targetId
                }
        }

    /** The offscreen document's session, with the binding frames come back through. */
    @Suppress("MissingUseCall") // Closed when the capture stops or the browser goes.
    private suspend fun offscreenSession(ext: Loaded): OpenCdpSession {
        document
            ?.takeIf { loaded === ext }
            ?.let {
                return it
            }
        val url = "chrome-extension://${ext.id}/offscreen.html"
        val deadline = now() + OFFSCREEN_WAIT_MS
        var target: String? = null
        while (target == null) {
            target =
                ext.connection.root
                    .call("Target.getTargets", buildJsonObject { put("filter", allTargets()) })
                    .decodeCdp<TargetList>()
                    ?.targetInfos
                    ?.firstOrNull { it.url == url && it.type == DOCUMENT_TYPE }
                    ?.targetId
            if (target == null) {
                if (now() >= deadline) {
                    throw ApiException.unavailable(
                        "capture_failed",
                        "The viewer extension's document did not open",
                    )
                }
                delay(OFFSCREEN_POLL_MS)
            }
        }
        val session = ext.connection.attach(target)
        session.listen { event ->
            if (event.method != "Runtime.bindingCalled") return@listen
            val called = event.params.decodeCdp<BindingCalled>() ?: return@listen
            if (called.name == BINDING) received(called.payload)
        }
        session.call("Runtime.addBinding", buildJsonObject { put("name", BINDING) })
        document = session
        return session
    }

    /**
     * One message from the offscreen document. It runs on the connection's reader, so it waits for
     * nothing.
     */
    private fun received(payload: String) {
        if (payload.isEmpty()) return
        val body = payload.substring(1)
        when (payload[0]) {
            'V' -> {
                val bytes = Base64.getDecoder().decode(body)
                videoViewers.toList().forEach { it.offer(bytes) }
            }
            'A' -> {
                val bytes = Base64.getDecoder().decode(body)
                if (bytes[0].toInt() == CONFIG) audioConfig = bytes
                audioViewers.toList().forEach { it.offer(bytes) }
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
            "video" -> scope.launch { announce(news.width, news.height) }
            "activated",
            "ended" -> scope.launch { follow() }
            "error" -> log.warn("The viewer's capture failed: {}", news.detail)
        }
    }

    /** Sends viewers a header for the new picture size, with the tab's size for their points. */
    private suspend fun announce(width: Int, height: Int) {
        val shown = captured ?: return
        val asked = spec ?: VideoSpec(DEFAULT_SIZE, DEFAULT_FPS, DEFAULT_BIT_RATE)
        val json =
            ApiJson.encodeToString(
                VideoHeader.serializer(),
                VideoHeader(
                    "avc",
                    width,
                    height,
                    asked.fps,
                    asked.bitRate,
                    shown.display.width,
                    shown.display.height,
                    ViewerPointer.MOUSE,
                ),
            )
        gate.withLock {
            header = json
            videoViewers.forEach { it.text(json) }
        }
    }

    /** Captures the tab Chrome shows now, when that is another tab. */
    private suspend fun follow() {
        gate.withLock {
            val ext = loaded ?: return
            if (captured == null) return
            try {
                val shown = showingTab() ?: return
                if (shown == captured?.pageId) return
                capture(ext, shown)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                log.warn("Could not follow the viewer to the tab Chrome shows: {}", e.message)
            }
        }
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
        captured = null
        header = null
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
            ?.takeIf { it.pageId == shown.pageId }
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
                session,
                ViewerPageInput(session, shown.display.pixelRatio, now),
            )
        input = made
        return made.input
    }

    /** The viewer's mouse and keyboard, on the captured tab. */
    private inner class Controls : ViewerControls {
        override val pointerKind: ViewerPointer = ViewerPointer.MOUSE

        override suspend fun contacts(frames: ReceiveChannel<PointerFrame>) {
            var down: Point? = null
            try {
                for (frame in frames) {
                    if (frame.op != PointerOp.CANCEL && frame.id != 0) continue
                    val at = Point(frame.x, frame.y)
                    when (frame.op) {
                        PointerOp.DOWN -> {
                            gate.withLock { pageInput() }.press(at)
                            down = at
                        }
                        PointerOp.MOVE ->
                            if (down != null) {
                                gate.withLock { pageInput() }.move(at, pressed = true)
                                down = at
                            }
                        PointerOp.UP ->
                            if (down != null) {
                                gate.withLock { pageInput() }.release(at)
                                down = null
                            }
                        PointerOp.CANCEL -> {
                            down?.let { gate.withLock { pageInput() }.release(it) }
                            down = null
                        }
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

        override suspend fun take(autoWake: Boolean) = Unit

        override suspend fun wake() = Unit

        override suspend fun key(request: KeyRequest): Any =
            gate.withLock { pageInput() }.key(request.key, request.metaState)

        override suspend fun text(request: TextRequest): Any =
            gate.withLock { pageInput() }.type(request.text)

        override suspend fun orientation(request: OrientationRequest): Any =
            throw ApiException(422, "unsupported", "A desktop browser has no orientation.")

        override suspend fun paste(request: ClipboardWriteRequest): Any =
            gate.withLock { pageInput() }.insert(request.text)

        override suspend fun pasteImage(bytes: ByteArray): Any =
            throw ApiException(
                422,
                "unsupported",
                "Pasting an image into a desktop tab is not supported.",
            )

        override suspend fun copy(): ViewerCopy {
            val selected = gate.withLock { pageInput() }.selection()
            return ViewerCopy(selected, null, changed = selected != null)
        }

        override suspend fun hover(at: ViewerHover): Any =
            gate.withLock { pageInput() }.move(Point(at.x, at.y), pressed = false)

        override suspend fun wheel(turn: ViewerWheel): Any =
            gate.withLock { pageInput() }.wheel(Point(turn.x, turn.y), turn.dx, turn.dy)
    }

    @Serializable private data class LoadedExtension(val id: String? = null)

    @Serializable private data class TargetList(val targetInfos: List<TargetInfo> = emptyList())

    @Serializable
    private data class TargetInfo(
        val targetId: String,
        val url: String? = null,
        val type: String? = null,
    )

    @Serializable
    private data class Attached(val sessionId: String? = null, val targetInfo: TargetInfo? = null)

    @Serializable private data class BindingCalled(val name: String, val payload: String)

    @Serializable private data class EvalReply(val result: EvalValue? = null)

    @Serializable private data class EvalValue(val value: JsonObject? = null)

    @Serializable
    private data class CaptureOutcome(val ok: Boolean? = null, val error: String? = null)

    @Serializable
    private data class News(
        val kind: String,
        val width: Int = 0,
        val height: Int = 0,
        val detail: String? = null,
    )

    @Serializable private data class Metrics(val cssVisualViewport: Viewport? = null)

    @Serializable
    private data class Viewport(val clientWidth: Double = 0.0, val clientHeight: Double = 0.0)

    private companion object {
        const val BINDING = "fingertipsViewerSend"
        /**
         * What DevTools lists an extension's offscreen document as. A tab of the same URL comes
         * too.
         */
        const val DOCUMENT_TYPE = "background_page"
        const val EXTENSION_PREFIX = "viewer-extension-"
        const val HASH_BYTES = 6
        val EXTENSION_FILES = listOf("manifest.json", "worker.js", "offscreen.html", "offscreen.js")
        val AUDIO_HEADER = AudioHeader("opus", 48_000, 2)
        const val CONFIG = 1
        const val ATTACH_WAIT_MS = 2_000L
        const val OFFSCREEN_WAIT_MS = 5_000L
        const val OFFSCREEN_POLL_MS = 100L
        const val DEFAULT_SIZE = 1280
        const val DEFAULT_FPS = 30
        const val DEFAULT_BIT_RATE = 4_000_000
        const val NANOS_PER_MS = 1_000_000L
    }
}

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

private fun allTargets() = buildJsonArray { add(buildJsonObject {}) }

private fun evaluation(expression: String) = buildJsonObject {
    put("expression", expression)
    put("awaitPromise", true)
    put("returnByValue", true)
}

private fun nothingShown() =
    ApiException.unavailable("capture_failed", "Chrome is showing no tab for the viewer.")

private val log = LoggerFactory.getLogger("FingertipsChrome.Viewer")
