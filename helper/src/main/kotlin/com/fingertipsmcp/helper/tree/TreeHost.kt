package com.fingertipsmcp.helper.tree

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.fingertipsmcp.helper.HelperJson
import com.fingertipsmcp.helper.ITreeClient
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.EventData
import com.fingertipsmcp.model.FindRequest
import com.fingertipsmcp.model.ImeState
import com.fingertipsmcp.model.NodeSelector
import com.fingertipsmcp.model.TreeActCall
import com.fingertipsmcp.model.TreeFocusCall
import com.fingertipsmcp.model.TreeFocusedText
import com.fingertipsmcp.model.TreeFound
import com.fingertipsmcp.model.TreeGlobalCall
import com.fingertipsmcp.model.TreeHoldCall
import com.fingertipsmcp.model.TreePackageCall
import com.fingertipsmcp.model.TreeResult
import com.fingertipsmcp.model.TreeScreened
import com.fingertipsmcp.model.TreeSetTextCall
import com.fingertipsmcp.model.TreeSnapshotCall
import com.fingertipsmcp.model.TreeTitleCall
import com.fingertipsmcp.model.TreeTouchCall
import com.fingertipsmcp.model.encodeEventData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer

/** UI tree for the shell helper. Live nodes stay in this process. */
@Suppress("InjectDispatcher") // The helper process has no app graph to inject a dispatcher from.
internal class TreeHost(private val client: () -> ITreeClient?) {
    private val json = HelperJson
    private val screen = Rect(0, 0, 1, 1)
    private var imePackage: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nodes = NodeRegistry()
    private val hold = ClientHold { client()?.asBinder() }
    private val session: UiAutomationSession
    private val tracker: UiChangeTracker
    private val nodeCache: NodeCacheAge
    private val content: ContentFrame
    private val snapshots: SnapshotEngine
    private val targeting = NodeTargeting { Rect(screen) }

    init {
        var created: UiChangeTracker? = null
        session =
            UiAutomationSession(
                onEvent = { event -> created?.onEvent(event) },
                onConnection = { up ->
                    if (up) created?.onConnected()
                    notify { it.onConnected(up) }
                },
                busy = { hold.held },
            )
        tracker =
            UiChangeTracker(
                sink = RemoteSink(),
                windows = { session.windows() },
                scope = scope,
                dispatcher = Dispatchers.IO,
            )
        created = tracker
        nodeCache = NodeCacheAge(session::clearNodeCache, SystemClock::uptimeMillis)
        content =
            ContentFrame(nodeCache) { pkg ->
                withWindows { ContentFrame.applicationRoots(it, pkg) }
            }
        snapshots =
            SnapshotEngine(
                windows = { session.windows() },
                io = Dispatchers.IO,
                seq = tracker.seq,
                imePackage = { imePackage },
                nodes = nodes,
                nodeCache = nodeCache,
                content = content,
                screenRect = { Rect(screen) },
            )
    }

    fun call(op: String, payload: String): String {
        val screened = json.decodeFromString(serializer<TreeScreened>(), payload)
        screen.set(0, 0, screened.widthPx.coerceAtLeast(1), screened.heightPx.coerceAtLeast(1))
        return try {
            done(dispatch(op, screened.body.ifBlank { "{}" }))
        } catch (e: ApiException) {
            result(TreeResult(e.status, e.error, e.message))
        } catch (e: SerializationException) {
            result(TreeResult(400, "bad_request", e.message))
        }
    }

    fun shutdown() {
        session.disconnect()
    }

    private fun dispatch(op: String, body: String): String =
        when (op) {
            "snapshot",
            "find",
            "keyboard",
            "pinpad",
            "contentBounds",
            "windowTitle" -> read(op, body)
            "setHold",
            "invalidate" -> sessionOp(op, body)
            else -> write(op, body)
        }

    private fun read(op: String, body: String): String =
        when (op) {
            "snapshot" -> {
                val call = json.decodeFromString(serializer<TreeSnapshotCall>(), body)
                val shot = runBlocking { snapshots.snapshot(call.options, call.summary) }
                json.encodeToString(shot)
            }
            "find" -> {
                val request = json.decodeFromString(serializer<FindRequest>(), body)
                json.encodeToString(runBlocking { snapshots.find(request) })
            }
            "keyboard" -> {
                val cleared = nodeCache.clear()
                json.encodeToString(withWindows { ImeKeyboard.scan(it, refresh = !cleared) })
            }
            "pinpad" -> {
                val cleared = nodeCache.clear()
                json.encodeToString(withWindows { PinPad.keys(it, refresh = !cleared) })
            }
            "contentBounds" -> {
                val call = json.decodeFromString(serializer<TreePackageCall>(), body)
                json.encodeToString(content.find(call.packageName))
            }
            "windowTitle" -> {
                val call = json.decodeFromString(serializer<TreeTitleCall>(), body)
                val found = withWindows { windows ->
                    windows.any { it.title?.contains(call.text, ignoreCase = true) == true }
                }
                json.encodeToString(TreeFound(found))
            }
            else -> throw ApiException.badRequest("Unknown tree op '$op'")
        }

    private fun sessionOp(op: String, body: String): String =
        when (op) {
            "invalidate" -> {
                snapshots.invalidate()
                "{}"
            }
            "setHold" -> {
                val call = json.decodeFromString(serializer<TreeHoldCall>(), body)
                hold.set(call.hold)
                if (call.hold) session.ensure() else session.poke()
                "{}"
            }
            else -> throw ApiException.badRequest("Unknown tree op '$op'")
        }

    private fun write(op: String, body: String): String =
        when (op) {
            "act" -> {
                val call = json.decodeFromString(serializer<TreeActCall>(), body)
                val result = nodes.withNode(call.ref) { semantic(it, call.action, call.text) }
                snapshots.invalidate()
                json.encodeToString(result)
            }
            "touchTarget" -> {
                val call = json.decodeFromString(serializer<TreeTouchCall>(), body)
                val rect = runBlocking {
                    nodes.withNode(call.ref) { node ->
                        targeting.touchTarget(node, session::windows, call.force)
                    }
                }
                json.encodeToString(rect)
            }
            "global" -> {
                val call = json.decodeFromString(serializer<TreeGlobalCall>(), body)
                json.encodeToString(ActionResult(ok = session.performGlobalAction(call.action)))
            }
            "setText" -> {
                val call = json.decodeFromString(serializer<TreeSetTextCall>(), body)
                json.encodeToString(setText(call))
            }
            "focus" -> {
                val call = json.decodeFromString(serializer<TreeFocusCall>(), body)
                json.encodeToString(focus(call.selector))
            }
            "selectFocused" -> json.encodeToString(selectFocused())
            "imeEnter" -> json.encodeToString(imeEnter())
            else -> throw ApiException.badRequest("Unknown tree op '$op'")
        }

    private fun <T> withWindows(block: (List<AccessibilityWindowInfo>) -> T): T {
        val open = session.windows()
        return try {
            block(open)
        } finally {
            NodeCompat.recycleAll(open)
        }
    }

    private fun semantic(node: AccessibilityNodeInfo, action: String, text: String?): ActionResult {
        if (
            action.equals(ActionNames.IME_ENTER, ignoreCase = true) &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        ) {
            throw ApiException(422, "unsupported", "imeEnter needs Android 11 or later")
        }
        val platform =
            ActionCatalog.forName(action)
                ?: ActionCatalog.custom(node, action)
                ?: throw ApiException.badRequest("Unknown action '$action'")
        val args =
            if (platform.id == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                val value = text ?: throw ApiException.badRequest("setText needs text")
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value,
                    )
                }
            } else {
                null
            }
        val ok = node.performAction(platform.id, args)
        return ActionResult(
            ok = ok,
            backend = "semantic",
            message = if (ok) null else "The node rejected '$action'",
        )
    }

    private fun setText(call: TreeSetTextCall): ActionResult {
        val node =
            focusedField()?.takeIf { it.isEditable }
                ?: throw if (content.nodesOff()) ContentFrame.pageNodesOff()
                else ApiException(409, "no_editor", "No text field is focused")
        try {
            val current =
                if (call.clear || node.isShowingHintText) "" else node.text?.toString().orEmpty()
            val args =
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        current + call.text,
                    )
                }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            val message =
                if (ok && call.submit) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        "submit needs Android 11 or later"
                    } else if (
                        node.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                        )
                    ) {
                        null
                    } else {
                        "The field rejected the IME action"
                    }
                } else {
                    null
                }
            snapshots.invalidate()
            return ActionResult(ok = ok, backend = "setText", message = message)
        } finally {
            NodeCompat.recycle(node)
        }
    }

    /**
     * The node with input focus. Chrome reports that as its whole view, with the page's field
     * further down, so a node that can't be edited is searched for a focused field that can. If
     * there is none, this is the node itself, or null when nothing has focus.
     */
    private fun focusedField(): AccessibilityNodeInfo? = nodeCache.read { findFocusedField() }

    private fun findFocusedField(): AccessibilityNodeInfo? {
        val focused = session.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (focused.isEditable) return focused
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until focused.childCount) focused.getChild(i)?.let(stack::add)
        var visited = 0
        var field: AccessibilityNodeInfo? = null
        while (field == null && stack.isNotEmpty() && visited++ < MAX_FIELD_SEARCH) {
            val node = stack.removeLast()
            if (node.isFocused && node.isEditable) {
                field = node
            } else {
                for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
                NodeCompat.recycle(node)
            }
        }
        stack.forEach(NodeCompat::recycle)
        if (field == null) return focused
        NodeCompat.recycle(focused)
        return field
    }

    private fun selectFocused(): TreeFocusedText {
        val selected = selectAll(focusedField())
        if (selected.length == null && content.nodesOff()) throw ContentFrame.pageNodesOff()
        return selected
    }

    private fun imeEnter(): ActionResult {
        val node =
            focusedField()
                ?: return ActionResult(ok = false, message = "No focused field to submit")
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return ActionResult(
                    ok = false,
                    message = "submit needs Android 11+ without an on-screen keyboard action key",
                )
            }
            val ok =
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            return ActionResult(
                ok = ok,
                backend = "setText",
                message = if (ok) null else "The field rejected the IME action",
            )
        } finally {
            NodeCompat.recycle(node)
        }
    }

    private fun focus(selector: NodeSelector?): ActionResult {
        val node =
            if (selector == null || !selector.targetsNode()) {
                focusedField() ?: throw ApiException.badRequest("No editable field is focused")
            } else {
                runBlocking { snapshots.resolve(selector) }
            }
        try {
            if (!node.isEditable) throw ApiException.badRequest("The node is not editable")
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            snapshots.invalidate()
            return ActionResult(ok = true, backend = "focus")
        } finally {
            NodeCompat.recycle(node)
        }
    }

    private fun NodeSelector.targetsNode(): Boolean =
        ref != null || index != null || !SelectorMatcher(this).isEmpty

    private fun done(body: String): String = result(TreeResult(body = body))

    private fun result(value: TreeResult): String = json.encodeToString(value)

    /** A dead client must not take down this process. Events arrive on the helper's looper. */
    private fun notify(block: (ITreeClient) -> Unit) {
        val current = client() ?: return
        try {
            block(current)
        } catch (e: RemoteException) {
            Log.w(TAG, "UI client is gone", e)
        }
    }

    private inner class RemoteSink : UiSink {
        private var ime = ImeState(visible = false)
        private var foreground: String? = null

        override fun emit(type: String, data: EventData) {
            val body = json.encodeEventData(data).toString()
            notify { it.onBus(type, body) }
        }

        override fun setIme(state: ImeState) {
            if (state == ime) return
            ime = state
            imePackage = state.packageName
            notify { it.onIme(json.encodeToString(state)) }
        }

        override fun setForeground(packageName: String?) {
            if (packageName == foreground) return
            foreground = packageName
            notify { it.onForeground(packageName.orEmpty()) }
        }

        override fun foreground(): String? = foreground

        override fun onTick(seq: Long, windowsVersion: Long, lastChangeMs: Long) {
            notify { it.onSeq(seq, windowsVersion, lastChangeMs) }
        }
    }

    private companion object {
        const val TAG = "FingertipsHelper"
    }
}

/** How many nodes [TreeHost] looks through for a field, which bounds a very large page. */
private const val MAX_FIELD_SEARCH = 5_000
