package com.fingertipsmcp.tree

import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.core.DeviceStateTracker
import com.fingertipsmcp.core.EventBus
import com.fingertipsmcp.helper.IHelper
import com.fingertipsmcp.helper.ITreeClient
import com.fingertipsmcp.helperclient.HelperConnection
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.DisplayInfo
import com.fingertipsmcp.model.EventData
import com.fingertipsmcp.model.EventTypes
import com.fingertipsmcp.model.FindRequest
import com.fingertipsmcp.model.FindResult
import com.fingertipsmcp.model.ImeState
import com.fingertipsmcp.model.NodeSelector
import com.fingertipsmcp.model.PinpadKeys
import com.fingertipsmcp.model.Rect
import com.fingertipsmcp.model.SnapshotOptions
import com.fingertipsmcp.model.ToastData
import com.fingertipsmcp.model.TreeActCall
import com.fingertipsmcp.model.TreeFocusCall
import com.fingertipsmcp.model.TreeFocusedText
import com.fingertipsmcp.model.TreeFound
import com.fingertipsmcp.model.TreeGlobalCall
import com.fingertipsmcp.model.TreeHoldCall
import com.fingertipsmcp.model.TreeKey
import com.fingertipsmcp.model.TreePackageCall
import com.fingertipsmcp.model.TreeResult
import com.fingertipsmcp.model.TreeScreened
import com.fingertipsmcp.model.TreeSetTextCall
import com.fingertipsmcp.model.TreeSnapshotCall
import com.fingertipsmcp.model.TreeTitleCall
import com.fingertipsmcp.model.TreeTouchCall
import com.fingertipsmcp.model.UiChangedData
import com.fingertipsmcp.model.UiSnapshot
import com.fingertipsmcp.model.WindowChangedData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer

/**
 * App-side client of the helper's UI tree. Live nodes stay in the helper; this process sends refs
 * and mirrors the sequence, IME, and foreground updates the helper pushes back.
 */
class TreeSession(
    private val helper: HelperConnection,
    private val bus: EventBus,
    private val state: DeviceStateTracker,
    private val display: () -> DisplayInfo,
    private val onSession: (Boolean) -> Unit = {},
) : ITreeClient.Stub() {
    private val seqFlow = MutableStateFlow(0L)
    private val windowsFlow = MutableStateFlow(0L)
    private val connectedFlow = MutableStateFlow(false)

    val seq: StateFlow<Long> = seqFlow.asStateFlow()
    val windowsVersion: StateFlow<Long> = windowsFlow.asStateFlow()
    val connected: StateFlow<Boolean> = connectedFlow.asStateFlow()

    @Volatile
    var lastChangeMs: Long = SystemClock.uptimeMillis()
        private set

    private val hold =
        HoldSync<IHelper>(
            send = { proxy, held -> call(proxy, "setHold", encode(TreeHoldCall(held))) },
            onFailure = { held, e ->
                Log.w(
                    TAG,
                    "Could not set the UI hold to $held; resending when the helper attaches",
                    e,
                )
            },
        )

    /** Keeps the helper's UiAutomation connection up while something is collecting events. */
    fun start(scope: CoroutineScope) {
        scope.launch { bus.subscribers.collect { count -> hold.want(count > 0, helper::require) } }
    }

    /** Called once a helper binder is accepted, so events have somewhere to land. */
    fun attach(proxy: IHelper) {
        proxy.setTreeClient(this)
        hold.attached(proxy)
    }

    fun snapshot(options: SnapshotOptions): UiSnapshot {
        val body =
            ApiJson.encodeToString(
                serializer<TreeSnapshotCall>(),
                TreeSnapshotCall(options, state.refresh()),
            )
        return decode(rpc("snapshot", body))
    }

    fun find(request: FindRequest): FindResult = decode(rpc("find", encode(request)))

    fun act(ref: String, action: String, text: String?): ActionResult =
        decode(rpc("act", encode(TreeActCall(ref, action, text))))

    fun touchTarget(ref: String, force: Boolean): Rect =
        decode(rpc("touchTarget", encode(TreeTouchCall(ref, force))))

    fun global(action: Int): Boolean {
        val result = decode<ActionResult>(rpc("global", encode(TreeGlobalCall(action))))
        return result.ok
    }

    fun setText(text: String, clear: Boolean, submit: Boolean): ActionResult =
        decode(rpc("setText", encode(TreeSetTextCall(text, clear, submit))))

    fun focus(selector: NodeSelector?): ActionResult =
        decode(rpc("focus", encode(TreeFocusCall(selector))))

    fun keyboard(): List<TreeKey> =
        ApiJson.decodeFromString(ListSerializer(TreeKey.serializer()), rpc("keyboard", "{}"))

    fun pinpad(): PinpadKeys = decode(rpc("pinpad", "{}"))

    fun contentBounds(packageName: String): Rect =
        decode(rpc("contentBounds", encode(TreePackageCall(packageName))))

    fun windowTitle(text: String): Boolean {
        val found = decode<TreeFound>(rpc("windowTitle", encode(TreeTitleCall(text))))
        return found.found
    }

    fun invalidate() {
        rpc("invalidate", "{}")
    }

    /** Characters in the focused field, after selecting them. Null when no field is in sight. */
    fun selectFocused(): Int? {
        val focused = decode<TreeFocusedText>(rpc("selectFocused", "{}"))
        return focused.length
    }

    fun imeEnter(): ActionResult = decode(rpc("imeEnter", "{}"))

    override fun onSeq(seq: Long, windowsVersion: Long, lastChangeMs: Long) {
        this.lastChangeMs = lastChangeMs
        seqFlow.value = seq
        windowsFlow.value = windowsVersion
    }

    override fun onBus(type: String, json: String) {
        val data =
            try {
                helperEventData(type, json)
            } catch (e: SerializationException) {
                Log.w(TAG, "Dropped a malformed $type event from the helper", e)
                return
            }
        if (data == null) {
            Log.w(TAG, "Dropped an unknown $type event from the helper")
            return
        }
        bus.emit(type, data)
    }

    override fun onIme(json: String) {
        val ime =
            runCatching { ApiJson.decodeFromString(ImeState.serializer(), json) }.getOrNull()
                ?: return
        state.setIme(ime)
    }

    override fun onForeground(packageName: String) {
        state.setForegroundPackage(packageName.takeIf { it.isNotEmpty() })
    }

    override fun onConnected(connected: Boolean) {
        connectedFlow.value = connected
        onSession(connected)
    }

    private fun rpc(op: String, body: String): String = call(helper.require(), op, body)

    private fun call(proxy: IHelper, op: String, body: String): String {
        val raw =
            try {
                proxy.tree(op, envelope(body))
            } catch (e: RemoteException) {
                throw ApiException.helperDropped(e)
            } catch (
                // Binder rethrows whatever the helper threw, and any of it is the helper's fault.
                @Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                throw ApiException(
                    503,
                    "helper_error",
                    e.message ?: "The helper tree call failed",
                    cause = e,
                )
            } ?: throw ApiException.helperDropped()
        return treeReplyBody(raw)
    }

    private fun envelope(body: String): String {
        val screen = display()
        return ApiJson.encodeToString(
            serializer<TreeScreened>(),
            TreeScreened(screen.widthPx, screen.heightPx, body),
        )
    }

    private inline fun <reified T> encode(value: T): String =
        ApiJson.encodeToString(serializer<T>(), value)

    private inline fun <reified T> decode(body: String): T = decodeHelperJson(serializer<T>(), body)
}

/** The body of the helper's tree [reply], or the error it reports. */
internal fun treeReplyBody(reply: String): String {
    val result = decodeHelperJson(TreeResult.serializer(), reply)
    if (result.status != 200) {
        throw ApiException(result.status, result.error ?: "tree_failed", result.message)
    }
    return result.body ?: "{}"
}

/**
 * Decodes JSON the helper sent. JSON that does not parse is the helper's fault, so it is a 503
 * `helper_error`, not the caller's `bad_request`.
 */
internal fun <T> decodeHelperJson(deserializer: DeserializationStrategy<T>, json: String): T =
    try {
        ApiJson.decodeFromString(deserializer, json)
    } catch (e: SerializationException) {
        throw ApiException(
            503,
            "helper_error",
            "The helper sent a reply that does not parse",
            cause = e,
        )
    }

/** Decodes an event the helper sent, or returns null for a type the helper does not send. */
private fun helperEventData(type: String, json: String): EventData? =
    when (type) {
        EventTypes.UI_CHANGED -> ApiJson.decodeFromString(UiChangedData.serializer(), json)
        EventTypes.WINDOW_CHANGED -> ApiJson.decodeFromString(WindowChangedData.serializer(), json)
        EventTypes.TOAST -> ApiJson.decodeFromString(ToastData.serializer(), json)
        else -> null
    }

private const val TAG = "FingertipsHelper"
