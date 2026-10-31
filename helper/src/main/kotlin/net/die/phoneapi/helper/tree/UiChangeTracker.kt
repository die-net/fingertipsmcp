package net.die.phoneapi.helper.tree

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.die.phoneapi.model.EventData
import net.die.phoneapi.model.EventTypes
import net.die.phoneapi.model.ImeState
import net.die.phoneapi.model.Rect as ModelRect
import net.die.phoneapi.model.ToastData
import net.die.phoneapi.model.UiChangedData
import net.die.phoneapi.model.WindowChangedData

data class WindowChange(
    val windowId: Int,
    val packageName: String?,
    val title: String?,
    val changes: List<String>,
)

/**
 * Turns accessibility events into a monotonic UI sequence number, bus events, and IME and
 * foreground-package state. [onEvent] runs on the main thread, so it only does bookkeeping; window
 * queries run on [dispatcher].
 */
interface UiSink {
    fun emit(type: String, data: EventData)

    fun setIme(state: ImeState)

    fun setForeground(packageName: String?)

    fun foreground(): String?

    fun onTick(seq: Long, windowsVersion: Long, lastChangeMs: Long)
}

class UiChangeTracker(
    private val sink: UiSink,
    private val windows: () -> List<AccessibilityWindowInfo>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val seqFlow = MutableStateFlow(0L)
    private val windowsFlow = MutableStateFlow(0L)
    private val windowChangeFlow =
        MutableSharedFlow<WindowChange>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Bumped on every observed content, scroll, focus, text or window change. */
    val seq: StateFlow<Long> = seqFlow.asStateFlow()

    /** Bumped after each window-list refresh (windows added, removed, restacked, refocused). */
    val windowsVersion: StateFlow<Long> = windowsFlow.asStateFlow()

    val windowChanges: SharedFlow<WindowChange> = windowChangeFlow.asSharedFlow()

    /** [SystemClock.uptimeMillis] of the last UI change, for idle detection. */
    @Volatile
    var lastChangeMs: Long = SystemClock.uptimeMillis()
        private set

    @Volatile
    var lastPackage: String? = null
        private set

    private val uiChangedPending = AtomicBoolean()
    private val pendingWindowChanges = ConcurrentHashMap<Int, Int>()
    private val windowPackages = ConcurrentHashMap<Int, String>()
    @Volatile private var lastUiChangedMs = 0L

    // One refresh at a time, so the IME, foreground and window events go out in order.
    private val windowsRefresh =
        SerialDebouncer(scope, dispatcher, WINDOWS_DEBOUNCE_MS, ::refreshWindows)

    fun onConnected() {
        scheduleWindowsRefresh()
    }

    fun onEvent(event: AccessibilityEvent) {
        val type = event.eventType
        val pkg = event.packageName?.toString()
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (event.className?.contains("Toast") == true) toast(event, pkg)
            return
        }
        if (pkg != null) lastPackage = pkg
        lastChangeMs = SystemClock.uptimeMillis()
        seqFlow.update { it + 1 }
        sink.onTick(seqFlow.value, windowsFlow.value, lastChangeMs)
        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                emitWindowState(event, pkg)
                scheduleWindowsRefresh()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                pendingWindowChanges.merge(event.windowId, event.windowChanges, Int::or)
                scheduleWindowsRefresh()
            }
            else -> Unit
        }
        scheduleUiChanged()
    }

    private fun toast(event: AccessibilityEvent, pkg: String?) {
        sink.emit(
            EventTypes.TOAST,
            ToastData(text = Redactor.maskCards(event.text.joinToString(" ")), packageName = pkg),
        )
    }

    private fun emitWindowState(event: AccessibilityEvent, pkg: String?) {
        val title = event.text.joinToString(" ").takeIf { it.isNotBlank() }
        sink.emit(
            EventTypes.WINDOW_CHANGED,
            WindowChangedData(
                windowId = event.windowId,
                packageName = pkg,
                title = title?.let { Redactor.maskCards(it) },
                className = event.className?.toString(),
                changes = listOf("state"),
            ),
        )
    }

    private fun scheduleUiChanged() {
        if (uiChangedPending.getAndSet(true)) return
        scope.launch(dispatcher) {
            val wait = lastUiChangedMs + UI_CHANGED_INTERVAL_MS - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            uiChangedPending.set(false)
            lastUiChangedMs = SystemClock.uptimeMillis()
            sink.emit(
                EventTypes.UI_CHANGED,
                UiChangedData(seq = seqFlow.value, packageName = lastPackage),
            )
        }
    }

    private fun scheduleWindowsRefresh() {
        windowsRefresh.request()
    }

    private fun refreshWindows() {
        val open =
            try {
                windows()
            } catch (e: IllegalStateException) {
                // The session can drop, or fail to reconnect, between an event and this refresh.
                // The next window event refreshes again.
                Log.w(TAG, "Skipped a window refresh", e)
                return
            }
        try {
            updateIme(open)
            sink.setForeground(foregroundPackage(open))
            emitWindowChanges(open)
            windowsFlow.update { it + 1 }
            sink.onTick(seqFlow.value, windowsFlow.value, lastChangeMs)
        } finally {
            NodeCompat.recycleAll(open)
        }
    }

    private fun updateIme(windows: List<AccessibilityWindowInfo>) {
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val next =
            if (ime == null) {
                ImeState(visible = false)
            } else {
                val bounds = Rect().also(ime::getBoundsInScreen)
                val touch = touchRegion(ime, bounds).bounds
                ImeState(visible = true, bounds = touch.toModel(), packageName = packageOf(ime))
            }
        sink.setIme(next)
    }

    private fun foregroundPackage(windows: List<AccessibilityWindowInfo>): String? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val window =
            apps.firstOrNull { it.isFocused }
                ?: apps.firstOrNull { it.isActive }
                ?: apps.maxByOrNull { it.layer }
        return window?.let(::packageOf) ?: sink.foreground()
    }

    private fun emitWindowChanges(windows: List<AccessibilityWindowInfo>) {
        for (id in pendingWindowChanges.keys) {
            val mask = pendingWindowChanges.remove(id) ?: continue
            val names =
                WINDOW_CHANGE_NAMES.filter { (bit, _) -> mask and bit != 0 }.map { it.second }
            if (names.isNotEmpty()) emitWindowChange(id, names, windows)
        }
    }

    private fun emitWindowChange(
        id: Int,
        names: List<String>,
        windows: List<AccessibilityWindowInfo>,
    ) {
        val window = windows.firstOrNull { it.id == id }
        val change =
            WindowChange(
                windowId = id,
                packageName = window?.let(::packageOf),
                title = window?.title?.toString(),
                changes = names,
            )
        if ("removed" in names) windowPackages.remove(id)
        windowChangeFlow.tryEmit(change)
        sink.emit(
            EventTypes.WINDOW_CHANGED,
            WindowChangedData(
                windowId = id,
                packageName = change.packageName,
                title = change.title?.let { Redactor.maskCards(it) },
                changes = names,
            ),
        )
    }

    private fun packageOf(window: AccessibilityWindowInfo): String? {
        windowPackages[window.id]?.let {
            return it
        }
        val root = window.root ?: return null
        val pkg = root.packageName?.toString()
        NodeCompat.recycle(root)
        if (pkg != null) {
            if (windowPackages.size > MAX_CACHED_WINDOWS) windowPackages.clear()
            windowPackages[window.id] = pkg
        }
        return pkg
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val UI_CHANGED_INTERVAL_MS = 100L
        const val WINDOWS_DEBOUNCE_MS = 30L
        const val MAX_CACHED_WINDOWS = 64

        /** Only changes an agent would care about; bounds, layer and children churn constantly. */
        val WINDOW_CHANGE_NAMES =
            listOf(
                AccessibilityEvent.WINDOWS_CHANGE_ADDED to "added",
                AccessibilityEvent.WINDOWS_CHANGE_REMOVED to "removed",
                AccessibilityEvent.WINDOWS_CHANGE_TITLE to "title",
                AccessibilityEvent.WINDOWS_CHANGE_ACTIVE to "active",
                AccessibilityEvent.WINDOWS_CHANGE_FOCUSED to "focused",
                AccessibilityEvent.WINDOWS_CHANGE_PIP to "pip",
            )
    }
}

internal fun Rect.toModel() = ModelRect(left, top, right, bottom)
