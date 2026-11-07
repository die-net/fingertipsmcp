package net.die.phoneapi.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.ApplicationTestBuilder
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.core.AppsService
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.ClipImage
import net.die.phoneapi.core.ClipboardService
import net.die.phoneapi.core.InputService
import net.die.phoneapi.core.IntentStart
import net.die.phoneapi.core.PowerService
import net.die.phoneapi.core.UiService
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.helperclient.ShellResult
import net.die.phoneapi.lease.ForegroundWatch
import net.die.phoneapi.lease.ScreenLease
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.AppInfo
import net.die.phoneapi.model.BrowserCaptureResult
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserSnapshotOptions
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.Capabilities
import net.die.phoneapi.model.ClipboardContent
import net.die.phoneapi.model.ClipboardReadRequest
import net.die.phoneapi.model.ClipboardWriteRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.ConsoleResult
import net.die.phoneapi.model.CreatedToken
import net.die.phoneapi.model.DeviceInfo
import net.die.phoneapi.model.DeviceStateSummary
import net.die.phoneapi.model.DisplayInfo
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.FindResult
import net.die.phoneapi.model.GestureRequest
import net.die.phoneapi.model.HelperStatus
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.ImeState
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.KeyguardState
import net.die.phoneapi.model.LaunchRequest
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.OrientationRequest
import net.die.phoneapi.model.PointerFrame
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.ScreenState
import net.die.phoneapi.model.SessionStarted
import net.die.phoneapi.model.SnapshotOptions
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.TokenInfo
import net.die.phoneapi.model.UiSnapshot
import net.die.phoneapi.model.UnlockRequest
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.WaitResult
import net.die.phoneapi.tabs.TabStore
import net.die.phoneapi.tabs.Tabs

internal class MemoryTokens : TokenGateway {
    private val bySecret = LinkedHashMap<String, TokenInfo>()

    fun issue(secret: String, scopes: Set<Scope>): String {
        bySecret[secret] = TokenInfo(id = secret, name = secret, scopes = scopes, createdAtMs = 0)
        return secret
    }

    override fun authenticate(secret: String): TokenInfo? = bySecret[secret]

    override fun list(): List<TokenInfo> = bySecret.values.toList()

    override fun create(name: String, scopes: Set<Scope>): CreatedToken {
        val secret = "created-$name"
        val info = TokenInfo(id = name, name = name, scopes = scopes, createdAtMs = 0)
        bySecret[secret] = info
        return CreatedToken(info, secret)
    }

    override fun setScopes(id: String, scopes: Set<Scope>): TokenInfo? {
        val entry = bySecret.entries.firstOrNull { it.value.id == id } ?: return null
        val updated = entry.value.copy(scopes = scopes)
        entry.setValue(updated)
        return updated
    }

    override fun revoke(id: String): Boolean {
        val secret = bySecret.entries.firstOrNull { it.value.id == id }?.key ?: return false
        bySecret.remove(secret)
        return true
    }
}

internal class RecordingWaits : WaitService {
    var calls = 0
    var request: WaitRequest? = null
    val requests = ArrayList<WaitRequest>()
    var scopes: Set<Scope>? = null

    override suspend fun wait(request: WaitRequest, scopes: Set<Scope>): WaitResult {
        calls++
        this.request = request
        requests += request
        this.scopes = scopes
        return WaitResult(matched = true, timedOut = false, elapsedMs = 1)
    }
}

/** Fakes of the service interfaces, so [phoneApiModule] can run under `testApplication`. */
@Suppress("InjectDispatcher") // The test server has no injected graph dispatcher.
internal class FakeApi(
    val browser: FakeBrowser = FakeBrowser(),
    /** Where tabs Chrome opens after an intent are looked for, for a test that runs its clock. */
    tabScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    /** The lease's clock, which moves only when a test moves it. */
    val clock = ManualClock()
    val tokens = MemoryTokens()
    val waits = RecordingWaits()
    var capabilities = allCapabilities()
    var inputTaps = 0
    var imeShow: ImeShowRequest? = null
    var orientation: OrientationRequest? = null
    var snapshotOptions: SnapshotOptions? = null
    var findRequest: FindRequest? = null
    /** The fake phone's clipboard, and what the last clipboard call asked for. */
    @Volatile var clip: String? = "on the phone"
    @Volatile var clipWrite: ClipboardWriteRequest? = null
    @Volatile var clipRead: ClipboardReadRequest? = null
    @Volatile var clipImage: ClipImage? = null
    @Volatile var clipPasted = false
    val pointerSessions = Channel<List<PointerFrame>>(capacity = Channel.UNLIMITED)
    /** Runs after a pointer stream's channel closes and before the pointer call returns. */
    @Volatile var afterPointer: suspend () -> Unit = {}
    private val cleanupCount = MutableStateFlow(0)

    /** How many times the lease ran cleanup, which it does on its own scope after a release. */
    var cleanups: Int
        get() = cleanupCount.value
        set(value) {
            cleanupCount.value = value
        }

    /** Waits until cleanup has run [count] times in all. */
    suspend fun awaitCleanups(count: Int) {
        cleanupCount.first { it >= count }
    }

    /** The autoWake of the last prepareForAction, which only the viewer's take calls here. */
    @Volatile var prepared: Boolean? = null

    val tabStore = MemoryTabStore()
    val lease: ScreenLease =
        ScreenLease(
            CoroutineScope(clock),
            cleanup = {
                cleanups++
                emptyList()
            },
            lockedNow = { true },
            now = { clock.now },
        )

    /**
     * Runs out the wait of a call that has just asked for the screen, at its real length, calling
     * [keepBusy] often enough for the holder to stay inside its grace and keep the screen.
     */
    suspend fun outwaitLease(keepBusy: suspend () -> Unit) {
        clock.awaitTimer(ScreenLease.WAIT_MS)
        val step = ScreenLease.GRACE_MS / 2
        repeat((ScreenLease.WAIT_MS / step).toInt()) {
            clock.advance(step)
            keepBusy()
        }
    }

    /** The app Android sends intents to. */
    @Volatile var intentApp: String? = "com.android.chrome"

    /** The package the fake phone has in front, which a test changes to act out a launch. */
    @Volatile var top: String? = "com.launcher"
    val tookScreen = mutableListOf<String>()
    val foreground =
        ForegroundWatch(
            lease,
            CoroutineScope(Dispatchers.Default),
            top = { top },
            bringBack = { top = it },
            report = { _, pkg -> synchronized(tookScreen) { tookScreen += pkg } },
        )

    val services: ServerServices =
        ServerServices(
            ui =
                object : UiService {
                    override suspend fun snapshot(options: SnapshotOptions): UiSnapshot {
                        snapshotOptions = options
                        return snapshot
                    }

                    override suspend fun find(request: FindRequest): FindResult {
                        findRequest = request
                        return FindResult(seq = 1, matches = emptyList())
                    }

                    override suspend fun act(ref: String, request: NodeActionRequest) = acted()
                },
            input =
                object : InputService {
                    override suspend fun tap(request: TapRequest): ActionResult {
                        inputTaps++
                        return acted()
                    }

                    override suspend fun swipe(request: SwipeRequest) = acted()

                    override suspend fun gesture(request: GestureRequest) = acted()

                    override suspend fun pointer(frames: ReceiveChannel<PointerFrame>) {
                        val seen = mutableListOf<PointerFrame>()
                        for (frame in frames) seen.add(frame)
                        afterPointer()
                        pointerSessions.send(seen)
                    }

                    override suspend fun key(request: KeyRequest) = acted()

                    override suspend fun text(request: TextRequest) = acted()

                    override suspend fun hideIme() = acted()

                    override suspend fun showIme(request: ImeShowRequest): ActionResult {
                        imeShow = request
                        return acted()
                    }
                },
            apps = FakeApps { intentApp },
            waits = waits,
            browser = browser,
            power =
                object : PowerService by FakePower {
                    override suspend fun orientation(request: OrientationRequest): ActionResult {
                        this@FakeApi.orientation = request
                        return acted()
                    }

                    override suspend fun prepareForAction(autoWake: Boolean): Boolean {
                        prepared = autoWake
                        return autoWake
                    }
                },
            clipboard =
                object : ClipboardService {
                    override suspend fun read(request: ClipboardReadRequest): ClipboardContent {
                        clipRead = request
                        return ClipboardContent(
                            text = clip,
                            mimeTypes =
                                listOfNotNull(clip?.let { "text/plain" }, clipImage?.mimeType),
                            changed = if (request.copy) false else null,
                        )
                    }

                    override suspend fun write(request: ClipboardWriteRequest): ActionResult {
                        clipWrite = request
                        clip = request.text
                        clipImage = null
                        return acted()
                    }

                    override suspend fun image(): ClipImage? = clipImage

                    override suspend fun writeImage(
                        bytes: ByteArray,
                        paste: Boolean,
                    ): ActionResult {
                        clip = null
                        clipImage = ClipImage(bytes, "image/png")
                        clipPasted = paste
                        return acted()
                    }

                    override suspend fun clearOwn(): ActionResult = acted()

                    override suspend fun clear(): ActionResult {
                        clip = null
                        clipImage = null
                        return acted()
                    }
                },
            tokens = tokens,
            ioDispatcher = Dispatchers.Unconfined,
            device =
                object : DeviceFacts {
                    override fun info(): DeviceInfo = deviceInfo(capabilities)

                    override fun capabilities(): Capabilities = capabilities

                    override fun versionName(): String = "test"
                },
            screenshots = { _ -> byteArrayOf(1, 2, 3) },
            writePin = { _ -> },
            events = emptyFlow(),
            video = VideoFeed { _, _ -> },
            audio = AudioFeed { _ -> },
            viewerHtml = { VIEWER_HTML },
            shell = { _ -> ShellResult(exit = 0, stdout = "log") },
            viewerText = { "http://127.0.0.1:1/viewer?access_token=TOKEN" },
            logcat = LogcatFeed { _ -> emptyFlow() },
            lease = lease,
            tabs =
                Tabs(
                    browser,
                    lease,
                    inFront = { false },
                    store = tabStore,
                    scope = tabScope,
                ),
            foreground = foreground,
        )
}

/** What [RawServer] got back. A reply to an upgrade has an empty [body] unless it was refused. */
internal data class RawReply(val status: Int, val body: String)

/**
 * A real CIO server, for requests that Ktor's test client can't make or can't report on: it decodes
 * a URL as it builds one, refuses to set `Upgrade`, and hides the status of a refused WebSocket
 * upgrade behind a 1 second wait.
 */
internal class RawServer(services: ServerServices) : AutoCloseable {
    // Not built in a coroutine scope, such as runTest's. There embeddedServer resolves to its
    // CoroutineScope overload, so the application runs on the test dispatcher, and stop() blocks
    // that dispatcher's thread until shutdownTimeout, 5 seconds by default, runs out.
    private val server = embeddedServer(CIO, host = LOOPBACK, port = 0) { phoneApiModule(services) }
    private val port = runBlocking {
        server.start(wait = false)
        server.engine.resolvedConnectors().first().port
    }

    fun get(target: String, vararg headers: String): RawReply =
        exchange(target, listOf("Connection: close") + headers)

    /** Sends a complete WebSocket handshake, so a route that takes sockets matches it. */
    fun upgrade(target: String, vararg headers: String): RawReply =
        exchange(target, HANDSHAKE + headers)

    private fun exchange(target: String, headers: List<String>): RawReply =
        Socket(LOOPBACK, port).use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            val lines = listOf("GET $target HTTP/1.1", "Host: $LOOPBACK") + headers
            val request = lines.joinToString("\r\n", postfix = "\r\n\r\n")
            socket.getOutputStream().write(request.toByteArray())
            val input = socket.getInputStream().buffered()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
            val length = CONTENT_LENGTH.find(head)?.groupValues?.get(1)?.toInt() ?: 0
            RawReply(
                status = head.toString().substringAfter(' ').substringBefore(' ').toInt(),
                body = input.readNBytes(length).decodeToString(),
            )
        }

    override fun close() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"

        /** A guard against a hang. It fires only when a test is already failing. */
        const val READ_TIMEOUT_MS = 5_000
        val CONTENT_LENGTH = Regex("(?im)^content-length: *(\\d+)")
        val HANDSHAKE =
            listOf(
                "Connection: Upgrade",
                "Upgrade: websocket",
                "Sec-WebSocket-Version: 13",
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
            )
    }
}

internal fun ApplicationTestBuilder.apiClient(): HttpClient = createClient {
    expectSuccess = false
    followRedirects = false
    install(WebSockets)
}

internal fun HttpRequestBuilder.bearer(secret: String) {
    header(HttpHeaders.Authorization, "Bearer $secret")
}

/** Starts a REST session for [secret], as a script does before acting on the phone. */
internal suspend fun startSession(http: HttpClient, secret: String): String {
    val body = http.post("/v1/sessions") { bearer(secret) }.bodyAsText()
    return ApiJson.decodeFromString(SessionStarted.serializer(), body).id
}

/** Names the REST session a request is for. */
internal fun HttpRequestBuilder.session(id: String) {
    header(SESSION_HEADER, id)
}

internal fun allCapabilities() =
    Capabilities(
        uiSnapshot = true,
        inputInject = true,
        textKeyevent = true,
        screenshotHelper = true,
        uiStableIds = true,
        appsManage = true,
        logcatAll = true,
        browserCdp = true,
        streamVideoMirror = true,
        streamAudioSubmix = true,
        encoderLowLatency = true,
        adbWireless = true,
        settingsSecure = true,
        clipboard = true,
    )

private fun deviceInfo(capabilities: Capabilities) =
    DeviceInfo(
        manufacturer = "test",
        model = "phoneapi-fake",
        sdkInt = 36,
        release = "16",
        appVersion = "test",
        display = DisplayInfo(1080, 2400, 420, 0, 60f),
        state =
            DeviceStateSummary(
                screen = ScreenState.ON,
                keyguard = KeyguardState(locked = false, secure = false),
                ime = ImeState(visible = false),
            ),
        helper = HelperStatus.RUNNING,
        uiAutomationConnected = true,
        capabilities = capabilities,
    )

private fun acted() = ActionResult(ok = true, backend = "fake")

private val snapshot =
    UiSnapshot(
        seq = 1,
        timestampMs = 0,
        state =
            DeviceStateSummary(
                screen = ScreenState.ON,
                keyguard = KeyguardState(locked = false, secure = false),
                ime = ImeState(visible = false),
            ),
        windows = emptyList(),
        compact = "compact",
    )

private class FakeApps(private val intentApp: () -> String?) : AppsService {
    override suspend fun list(launchableOnly: Boolean): List<AppInfo> = emptyList()

    override suspend fun launch(packageName: String, request: LaunchRequest) = acted()

    override suspend fun stop(packageName: String) = acted()

    override suspend fun clear(packageName: String) = acted()

    override suspend fun intent(request: IntentRequest) = IntentStart(acted(), intentApp())
}

/** A browser_read with this selector fails, as when the page goes away during the read. */
internal const val FAILING_SELECTOR = "#fails"

/** A browser with one tab, which [BrowserService.openTab] opens and reads and finds answer. */
internal open class FakeBrowser : BrowserService {
    private val tab =
        BrowserTarget(
            id = "chrome_devtools_remote~t",
            type = "page",
            socket = "chrome_devtools_remote",
        )

    /** Whether Chrome is stopped, so opening a tab starts it and takes the screen. */
    var stopped = false

    override suspend fun chromeStopped() = stopped

    // Chrome has the tab, so a session can claim it without opening it first.
    override suspend fun targets(): List<BrowserTarget> = listOf(tab)

    override suspend fun openTab(
        url: String,
        capture: Boolean,
        incognito: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget {
        created(tab.id)
        return tab
    }

    override suspend fun navigate(id: String, url: String) =
        BrowserTarget(id = id, type = "page", url = url, socket = "s")

    override suspend fun capture(id: String, on: Boolean) =
        BrowserCaptureResult(capturing = on, responses = if (on) 2 else 0)

    override suspend fun closeTab(id: String) = acted()

    override suspend fun activate(id: String) = Unit

    override suspend fun showing(id: String) = false

    override suspend fun snapshot(id: String, options: BrowserSnapshotOptions) =
        BrowserSnapshot(id = id, compact = "page")

    override suspend fun read(id: String, request: BrowserReadRequest): BrowserReadResult {
        if (request.selector == FAILING_SELECTOR) {
            throw ApiException(502, "cdp_error", "The page went away.")
        }
        return BrowserReadResult(
            extracted = "page",
            version = "0",
            totalChars = 4,
            offset = 0,
            returnedChars = 4,
            truncated = false,
            content = "page",
        )
    }

    override suspend fun find(id: String, request: BrowserFindRequest) =
        BrowserFindResult(extracted = "page", version = "0", totalChars = 4, total = 0)

    override suspend fun tap(id: String, request: BrowserTapRequest) = acted()

    override suspend fun swipe(id: String, request: BrowserSwipeRequest) = acted()

    override suspend fun gesture(id: String, request: BrowserGestureRequest) = acted()

    override suspend fun key(id: String, request: BrowserKeyRequest) = acted()

    override suspend fun text(id: String, request: BrowserTextRequest) = acted()

    // A tab a url read opens has loaded at once.
    override suspend fun evaluate(id: String, request: EvalRequest) =
        if (request.expression.endsWith("document.readyState"))
            EvalResult(value = JsonPrimitive("complete"))
        else EvalResult()

    override suspend fun console(id: String, request: ConsoleRequest) = ConsoleResult()
}

private object FakePower : PowerService {
    override suspend fun wake() = acted()

    override suspend fun unlock(request: UnlockRequest) = acted()

    override suspend fun lock() = acted()

    override suspend fun orientation(request: OrientationRequest) = acted()

    override suspend fun prepareForAction(autoWake: Boolean) = false
}

private val VIEWER_HTML = "<html>viewer</html>".encodeToByteArray()

internal class MemoryTabStore : TabStore {
    var ids: Set<String> = emptySet()

    override fun load(): Set<String> = ids

    override fun save(ids: Set<String>) {
        this.ids = ids
    }
}
