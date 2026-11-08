package com.fingertipsmcp

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log
import com.fingertipsmcp.apps.ActivityStart
import com.fingertipsmcp.apps.AppsServiceImpl
import com.fingertipsmcp.apps.RESUMED_ACTIVITY_ARGS
import com.fingertipsmcp.apps.ownProcessInFront
import com.fingertipsmcp.apps.resumedPackage
import com.fingertipsmcp.browser.BrowserServiceImpl
import com.fingertipsmcp.browser.CdpForward
import com.fingertipsmcp.browser.ChromeKeeper
import com.fingertipsmcp.browser.DevtoolsPreconditions
import com.fingertipsmcp.browser.DevtoolsSocket
import com.fingertipsmcp.browser.FREEZER_ARGS
import com.fingertipsmcp.browser.OPEN_PRIVATE_TAB
import com.fingertipsmcp.browser.TabProbe
import com.fingertipsmcp.browser.USB_DEVTOOLS_SOCKET
import com.fingertipsmcp.browser.UsbDevtools
import com.fingertipsmcp.browser.freezerOn
import com.fingertipsmcp.browser.pdfSource
import com.fingertipsmcp.core.AppsService
import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.core.ClipboardService
import com.fingertipsmcp.core.DeviceInfoProvider
import com.fingertipsmcp.core.DeviceStateTracker
import com.fingertipsmcp.core.EventBus
import com.fingertipsmcp.core.InputService
import com.fingertipsmcp.core.PowerService
import com.fingertipsmcp.core.Screenshotter
import com.fingertipsmcp.core.SettingsStore
import com.fingertipsmcp.core.UiService
import com.fingertipsmcp.core.WaitService
import com.fingertipsmcp.core.awaitChecked
import com.fingertipsmcp.core.awaitDeviceState
import com.fingertipsmcp.helperclient.AdbEndpointFinder
import com.fingertipsmcp.helperclient.AdbIdentity
import com.fingertipsmcp.helperclient.HelperConnection
import com.fingertipsmcp.helperclient.HelperLogcatFeed
import com.fingertipsmcp.helperclient.HelperShell
import com.fingertipsmcp.helperclient.HelperStatusNotifier
import com.fingertipsmcp.helperclient.HelperSupervisor
import com.fingertipsmcp.helperclient.KeystorePrivateKeyStore
import com.fingertipsmcp.helperclient.NsdRecordBrowser
import com.fingertipsmcp.helperclient.SecureSettings
import com.fingertipsmcp.helperclient.WirelessPairing
import com.fingertipsmcp.helperclient.WirelessSetting
import com.fingertipsmcp.helperclient.grantSecureSettings
import com.fingertipsmcp.input.ClipboardServiceImpl
import com.fingertipsmcp.input.Humanizer
import com.fingertipsmcp.input.InjectKeyBackend
import com.fingertipsmcp.input.InjectTouchBackend
import com.fingertipsmcp.input.InputServiceImpl
import com.fingertipsmcp.input.TouchInput
import com.fingertipsmcp.input.asResult
import com.fingertipsmcp.lease.ForegroundWatch
import com.fingertipsmcp.lease.ScreenCleanup
import com.fingertipsmcp.lease.ScreenLease
import com.fingertipsmcp.mcp.McpSessions
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.EventTypes
import com.fingertipsmcp.model.HelperStatus
import com.fingertipsmcp.model.KeyRequest
import com.fingertipsmcp.model.ScreenState
import com.fingertipsmcp.model.ScreenTakenData
import com.fingertipsmcp.power.PinStore
import com.fingertipsmcp.power.PowerServiceImpl
import com.fingertipsmcp.server.ApiServer
import com.fingertipsmcp.server.AudioFeed
import com.fingertipsmcp.server.DeviceFacts
import com.fingertipsmcp.server.NetworkWatcher
import com.fingertipsmcp.server.ServerController
import com.fingertipsmcp.server.ServerServices
import com.fingertipsmcp.server.TokenStore
import com.fingertipsmcp.server.VideoFeed
import com.fingertipsmcp.server.fingertipsModule
import com.fingertipsmcp.stream.AudioStream
import com.fingertipsmcp.stream.StreamLease
import com.fingertipsmcp.stream.VideoStream
import com.fingertipsmcp.tabs.FileTabStore
import com.fingertipsmcp.tabs.Tabs
import com.fingertipsmcp.tree.HelperUiService
import com.fingertipsmcp.tree.PageNodes
import com.fingertipsmcp.tree.TreeSession
import com.fingertipsmcp.wait.WaitServiceImpl
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process-wide object graph, created once by [FingertipsApp]. */
class AppGraph(
    val context: Context,
    filesDir: File,
    val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val bus = EventBus()
    val settings = SettingsStore(filesDir)
    val tokens = TokenStore(filesDir)
    val state = DeviceStateTracker(context, bus)
    val helper =
        HelperConnection(
            bus,
            idleStatus =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HelperStatus.NEEDS_PAIRING
                } else {
                    HelperStatus.NEEDS_USB
                },
        )
    val shell = HelperShell(helper, ioDispatcher)
    val network = NetworkWatcher(context)
    private val adbKeys = KeystorePrivateKeyStore(filesDir)
    private val adbEndpoints =
        AdbEndpointFinder(helper, ioDispatcher, AdbIdentity(filesDir), NsdRecordBrowser(context))
    private val wirelessSetting =
        SecureSettings(context.contentResolver).let {
            WirelessSetting(read = it::wirelessEnabled, write = it::setWirelessEnabled)
        }
    internal val helperSupervisor =
        HelperSupervisor(
            context = context,
            helper = helper,
            keys = adbKeys,
            finder = adbEndpoints,
            network = network,
            setting = wirelessSetting,
            scope = scope,
            ioDispatcher = ioDispatcher,
        )
    private val cdpForward =
        CdpForward(context, adbKeys, adbEndpoints, wirelessSetting, ioDispatcher)
    private val usbDevtools =
        UsbDevtools(
            openSocket = { helper.require().openAbstractSocket(USB_DEVTOOLS_SOCKET) },
            io = ioDispatcher,
        )
    internal val wirelessPairing =
        WirelessPairing(context, helperSupervisor, adbEndpoints, scope, ioDispatcher)

    /** The lock-screen PIN, provisioned over ADB and never returned by the API. */
    val pins = PinStore(filesDir)

    @Volatile private var uiAutomationConnected = false

    private val chromeKeeper = ChromeKeeper(context)
    @Volatile private var appFreezer: Boolean? = null

    val deviceInfo =
        DeviceInfoProvider(
            context = context,
            state = state,
            isHelperRunning = { helper.isRunning },
            isUiAutomationConnected = { uiAutomationConnected },
            helperStatus = { helper.status.value },
            helperRecoveredAtMs = { helper.recoveredAtMs },
            appFreezer = { appFreezer },
            chromeKept = { chromeKeeper.held },
        )

    val tree =
        TreeSession(
            helper = helper,
            bus = bus,
            state = state,
            display = { deviceInfo.display() },
            onSession = { up -> uiAutomationConnected = up },
        )

    val keys = InjectKeyBackend(helper, ioDispatcher)
    private val touchBackend = InjectTouchBackend(helper, ioDispatcher)
    val touch = TouchInput(touchBackend, Humanizer()) { deviceInfo.display() }
    val screenshots = Screenshotter(ioDispatcher, ::helperScreenshot)
    val power: PowerService =
        PowerServiceImpl(
            context = context,
            settings = settings,
            seq = tree.seq,
            tree = tree,
            state = state,
            bus = bus,
            shell = shell,
            pins = pins,
            touch = touch,
            display = { deviceInfo.display() },
            io = ioDispatcher,
        )
    val ui: UiService =
        HelperUiService(
            prepare = power::prepareForAction,
            tree = tree,
            touch = touch,
            seq = tree.seq,
        )
    val input: InputService =
        InputServiceImpl(
            prepare = power::prepareForAction,
            io = ioDispatcher,
            tree = tree,
            seq = tree.seq,
            touch = touch,
            keys = keys,
            state = state,
            display = { deviceInfo.display() },
            pageNodes =
                PageNodes(
                    start = { pkg -> launchPackage(pkg) },
                    bounds = { pkg -> tree.contentBounds(pkg) },
                ),
        )
    val clipboard: ClipboardService = ClipboardServiceImpl(context, helper, input, ioDispatcher)
    val apps: AppsService =
        AppsServiceImpl(
            context = context,
            io = ioDispatcher,
            prepare = power::prepareForAction,
            invalidateSnapshots = tree::invalidate,
            seq = tree.seq,
            shell = shell,
            awaitForeground = { pkg ->
                awaitDeviceState(state, bus, FOREGROUND_WAIT_MS) { it.foregroundPackage == pkg }
            },
            inFront = ::ownProcessInFront,
        )
    private val chromeDialog = ChromeDialog(state::refresh, tree::find, ioDispatcher)
    val browser: BrowserService = browserService()
    // Cleanup runs only after a holder lets go, long after tabs exists.
    @Suppress("PropertyUsedBeforeDeclaration")
    private val screenCleanup =
        ScreenCleanup(
            input,
            power,
            clipboard,
            showPlaceholder = { tabs.showPlaceholder() },
            awaitHome = ::awaitHome,
        )
    private val screenLease: ScreenLease =
        ScreenLease(
            scope,
            cleanup = screenCleanup::run,
            ready = screenCleanup::ready,
            lockedNow = { state.refresh().keyguard.locked },
        )
    private val tabs: Tabs =
        Tabs(
            browser,
            screenLease,
            inFront = { pkg -> appInFront(pkg) },
            store = FileTabStore(File(filesDir, "session-tabs")),
            scope = scope,
        )
    val waits: WaitService =
        WaitServiceImpl(
            prepare = power::prepareForAction,
            tree = tree,
            state = state,
            bus = bus,
            io = ioDispatcher,
            browser = browser,
            openDevtools = ::openDevtools,
            lease = screenLease.state,
            // Unknown, with the screen dark or the helper down, counts as no dialog: the wait
            // then sets the page up as for any other.
            dialogFrom = { host -> chromeDialog.shown(host) == true },
        )
    internal val video =
        VideoStream(
            StreamLease(context, settings),
            helper,
            { deviceInfo.display() },
            context,
            ioDispatcher,
        )
    internal val audio = AudioStream(StreamLease(context, settings), helper, ioDispatcher)

    val services =
        ServerServices(
            ui = ui,
            input = input,
            apps = apps,
            waits = waits,
            browser = browser,
            power = power,
            clipboard = clipboard,
            tokens = tokens,
            ioDispatcher = ioDispatcher,
            device = GraphDevice(deviceInfo),
            screenshots = { scale -> this.screenshots.png(scale) },
            writePin = { pin -> pins.write(pin) },
            events = bus.events,
            video = VideoFeed { session, spec -> this.video.serve(session, spec) },
            audio = AudioFeed { session -> this.audio.serve(session) },
            viewerHtml = { asset("viewer.html") },
            shell = { argv -> this.shell.exec(argv) },
            viewerText = { viewerLink() },
            logcat = HelperLogcatFeed(helper, bus, ioDispatcher),
            lease = screenLease,
            tabs = tabs,
            foreground =
                ForegroundWatch(
                    screenLease,
                    scope,
                    top = { topPackage() },
                    bringBack = { pkg -> bringBack(pkg) },
                    report = { holder, pkg -> tookScreen(McpSessions.hashOf(holder.id), pkg) },
                ),
        )

    val server = ApiServer(context.packageName, ioDispatcher) { fingertipsModule(services) }
    val serverController = ServerController(scope, server::start, server::stop)

    private suspend fun asset(name: String): ByteArray =
        withContext(ioDispatcher) { context.assets.open(name).use { it.readBytes() } }

    @Suppress("MissingUseCall") // The caller closes the socket.
    private suspend fun openDevtools(name: String, readTimeoutMs: Long): DevtoolsSocket =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cdpForward.open(name, readTimeoutMs)
        } else {
            usbDevtools.open(name, readTimeoutMs)
        }

    /**
     * Screen on, keyguard down, and [pkg]'s task is on top, even with no web content showing, such
     * as Chrome's start screen. Asks the helper, since foregroundPackage can be stale.
     */
    private suspend fun appInFront(pkg: String): Boolean {
        val now = state.refresh()
        if (now.screen != ScreenState.ON || now.keyguard.locked) return false
        return topPackage() == pkg
    }

    private suspend fun launchPackage(pkg: String): ActivityStart =
        shell.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

    private suspend fun openPrivateTab(pkg: String) {
        val started =
            shell.startActivity(
                Intent(OPEN_PRIVATE_TAB).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        if (started.failure != null) {
            throw ApiException.unavailable(
                "browser_unavailable",
                "Chrome would not open an incognito tab: ${started.message}",
            )
        }
    }

    private suspend fun readFreezer(): Boolean? =
        try {
            freezerOn(shell.exec(FREEZER_ARGS).stdout)
        } catch (_: ApiException) {
            null
        }

    /**
     * The package of the top task, or null when the helper cannot tell. The helper asks the
     * activity task manager directly, in milliseconds; `dumpsys` is the fallback for a release
     * where that call fails, at about 100 ms on a phone.
     */
    private suspend fun topPackage(): String? {
        val proxy =
            try {
                helper.require()
            } catch (_: ApiException) {
                return null
            }
        val direct = withContext(ioDispatcher) { runCatching { proxy.topPackage() } }
        direct.onSuccess {
            return it
        }
        return try {
            val activities = shell.exec(RESUMED_ACTIVITY_ARGS)
            if (activities.ok) resumedPackage(activities.stdout) else null
        } catch (_: ApiException) {
            null
        }
    }

    /**
     * Waits for the home app to be the top task after HOME. With the screen dark or locked, HOME
     * moves nothing, and with the top task or the home app not known, nothing can be waited for.
     * False when another app stayed on top.
     */
    private suspend fun awaitHome(): Boolean {
        val home = homePackage() ?: return true
        return awaitChecked(merge(state.state, bus.events), HOME_WAIT_MS) {
            val now = state.refresh()
            now.screen != ScreenState.ON ||
                now.keyguard.locked ||
                topPackage().let { it == null || it == home }
        }
    }

    /** The package HOME goes to, or null when Android would ask which one. */
    private suspend fun homePackage(): String? =
        withContext(ioDispatcher) {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val found = context.packageManager.resolveActivity(home, 0)
            found?.activityInfo?.packageName?.takeIf { it != CHOOSER_PACKAGE }
        }

    /** Puts [pkg] back in front, or goes home when it has no launcher entry, as a launcher. */
    private suspend fun bringBack(pkg: String) {
        try {
            putBack(pkg, ::launchPackage) { input.key(KeyRequest(key = "HOME")) }
        } catch (_: ApiException) {
            // The helper is gone; the holder's next result says to check the screen.
        }
    }

    private fun tookScreen(session: String, pkg: String) {
        Log.w(TAG, "MCP session $session brought $pkg forward without holding the screen")
        bus.emit(EventTypes.SCREEN_TAKEN, ScreenTakenData(session, pkg))
    }

    @Suppress("MissingUseCall") // BrowserServiceImpl closes each socket it opens.
    private fun browserService(): BrowserService =
        BrowserServiceImpl(
            io = ioDispatcher,
            listSockets = { helper.require().listDevtoolsSockets() },
            open = { name -> openDevtools(name, DEVTOOLS_READ_MS) },
            openProbe = { name -> openDevtools(name, TabProbe.PROBE_MS) },
            openCapture = { name -> openDevtools(name, 0) },
            contentBounds = { pkg -> tree.contentBounds(pkg.orEmpty()) },
            touchAt = { rect, humanize -> touch.tap(rect, humanize = humanize).asResult() },
            swipeAt = { from, to, spec, page -> touch.swipe(from, to, spec, page).asResult() },
            gestureAt = { pointers, humanize, page ->
                touch.gesture(pointers, humanize, page).asResult()
            },
            pressKey = { request -> input.key(request) },
            typeText = { request -> input.text(request) },
            prepare = { autoWake -> power.prepareForAction(autoWake) },
            sequence = { tree.seq.value },
            invalidateSnapshots = { tree.invalidate() },
            showPackage = { pkg -> launchPackage(pkg) },
            inFront = { pkg -> appInFront(pkg) },
            // Unknown counts as no dialog, so the call goes on as for any other timeout.
            pageDialog = { chromeDialog.shown() == true },
            pageScript = {
                asset("readability/Readability.js").decodeToString() +
                    "\n" +
                    asset("page-pending.js").decodeToString() +
                    "\n" +
                    asset("page-data.js").decodeToString() +
                    "\n" +
                    asset("page-read.js").decodeToString()
            },
            dataScript = { asset("page-data.js").decodeToString() },
            pendingScript = { asset("page-pending.js").decodeToString() },
            pdfScript = {
                pdfSource(
                    asset("pdfjs/pdf.worker.min.mjs").decodeToString(),
                    asset("pdfjs/pdf.min.mjs").decodeToString(),
                    asset("pdf-read.js").decodeToString(),
                )
            },
            devtoolsGap = DevtoolsPreconditions(context)::explain,
            refreshHz = { deviceInfo.display().refreshRate },
            touchscreen = { touchBackend.touchscreen() },
            openPrivateTab = { pkg -> openPrivateTab(pkg) },
            scope = scope,
        )

    fun start() {
        state.start()
        network.start()
        tree.start(scope)
        helper.onReady = { proxy ->
            tree.attach(proxy)
            scope.launch { tabs.sweep() }
            scope.launch { appFreezer = readFreezer() }
        }
        // Chrome stays unfrozen whenever the helper runs, whether or not anyone is connected.
        scope.launch {
            helper.status.collect { status ->
                if (status == HelperStatus.RUNNING) chromeKeeper.hold() else chromeKeeper.drop()
            }
        }
        scope.launch {
            helper.status
                .filter { it == HelperStatus.RUNNING }
                .collect { grantSecureSettings(context, shell) }
        }
        HelperStatusNotifier(context, helper).start(scope)
        helperSupervisor.start()
    }

    private suspend fun helperScreenshot(): ByteArray? {
        val proxy = helper.require()
        val pipe = withContext(ioDispatcher) { proxy.screencap() } ?: return null
        return withContext(ioDispatcher) {
            try {
                FileInputStream(pipe.fileDescriptor).use { it.readBytes() }
            } finally {
                pipe.close()
            }
        }
    }

    fun screenRect(): Rect = deviceInfo.display().let { Rect(0, 0, it.widthPx, it.heightPx) }

    private fun viewerLink(): String {
        val port = settings.current.port
        return "http://127.0.0.1:$port/viewer?access_token=TOKEN\n" +
            "Replace TOKEN with this device's bearer token. " +
            "That URL sets a cookie and redirects to /viewer without the token. " +
            "The query parameter is also accepted on WebSocket upgrades. " +
            "Expose the server on the computer first with `fingertips server` " +
            "(or `fingertips pair` to mint a token). " +
            "Expected local port $port for package ${context.packageName}."
    }

    private companion object {
        const val DEVTOOLS_READ_MS = 15_000L
        const val FOREGROUND_WAIT_MS = 5_000L
        /** How long HOME may take to reach the screen. */
        const val HOME_WAIT_MS = 3_000L
        /** What Android resolves HOME to when no home app is the default: its chooser. */
        const val CHOOSER_PACKAGE = "android"
        const val TAG = "FingertipsLease"
    }
}

/**
 * Starts [pkg]'s launcher activity with [launch], and goes [home] when Android reports that it
 * could not, as for a package with no launcher entry.
 */
internal suspend fun putBack(
    pkg: String,
    launch: suspend (String) -> ActivityStart,
    home: suspend () -> Unit,
) {
    if (launch(pkg).failure != null) home()
}

private class GraphDevice(private val provider: DeviceInfoProvider) : DeviceFacts {
    override fun info() = provider.info()

    override fun capabilities() = provider.capabilities()

    override fun versionName() = provider.versionName()
}
