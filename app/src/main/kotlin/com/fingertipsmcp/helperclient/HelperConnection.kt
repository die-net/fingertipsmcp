package com.fingertipsmcp.helperclient

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.fingertipsmcp.core.EventBus
import com.fingertipsmcp.helper.IHelper
import com.fingertipsmcp.helper.Registration
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.EventTypes
import com.fingertipsmcp.model.HelperStatus
import com.fingertipsmcp.model.HelperStatusData
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the helper's Binder once it registers, and tracks its liveness. */
class HelperConnection(private val bus: EventBus, private val idleStatus: HelperStatus) {
    private val statusFlow = MutableStateFlow(idleStatus)
    @Volatile private var helper: IHelper? = null
    @Volatile private var binder: IBinder? = null

    @Volatile
    var recoveredAtMs: Long? = null
        private set

    val status: StateFlow<HelperStatus> = statusFlow.asStateFlow()

    /** Runs after the helper process dies, once [status] has returned to the idle value. */
    @Volatile var onDied: (() -> Unit)? = null

    /** Runs when a helper binder is refused, so the supervisor can start a current one. */
    @Volatile var onRejected: (() -> Unit)? = null

    /** Runs after a helper binder is accepted. */
    @Volatile var onReady: ((IHelper) -> Unit)? = null

    private val deathListeners = CopyOnWriteArrayList<() -> Unit>()
    private val readyListeners = CopyOnWriteArrayList<(IHelper) -> Unit>()

    /**
     * Adds a callback that runs on the registering Binder thread each time a helper binder is
     * accepted, after [onReady]. It may call the helper, and should return quickly.
     */
    fun addReadyListener(listener: (IHelper) -> Unit) {
        readyListeners += listener
    }

    private val deathRecipient = IBinder.DeathRecipient { onHelperDied() }

    /** Adds a callback that runs when the helper process dies, before [onDied]. */
    fun addDeathListener(listener: () -> Unit) {
        deathListeners += listener
    }

    private fun onHelperDied() {
        synchronized(this) {
            helper = null
            binder = null
        }
        Log.w(TAG, "Helper died")
        setStatus(idleStatus)
        deathListeners.forEach { it() }
        onDied?.invoke()
    }

    val isRunning: Boolean
        get() = helper != null

    /** Asks the helper process to exit. The death callback clears [status]. */
    fun shutdown() {
        val proxy = helper ?: return
        try {
            proxy.shutdown()
        } catch (e: RemoteException) {
            Log.w(TAG, "Could not stop the helper", e)
        }
    }

    /** Returns the helper or throws `503 helper_unavailable`, naming how to start it. */
    fun require(): IHelper = helper ?: throw unavailable()

    /** The `503 helper_unavailable` for a call that needs the helper because of [why]. */
    fun unavailable(why: String? = null): ApiException =
        ApiException.helperUnavailable(listOfNotNull(why, whyUnavailable()).joinToString(" "))

    private fun whyUnavailable(): String =
        when (statusFlow.value) {
            HelperStatus.STARTING -> "It is starting. Retry in a moment."
            HelperStatus.NEEDS_PAIRING ->
                "Pair wireless debugging in FingertipsMCP, or start the helper over USB."
            HelperStatus.NEEDS_USB ->
                "This Android version has no wireless debugging. Start the helper over USB."
            HelperStatus.STOPPED,
            HelperStatus.RUNNING ->
                "Start it over USB, or pair wireless debugging on Android 11 or later."
        }

    fun getOrNull(): IHelper? = helper

    @Synchronized
    fun register(newBinder: IBinder): Boolean {
        val proxy = IHelper.Stub.asInterface(newBinder)
        val version =
            try {
                proxy.protocolVersion()
            } catch (e: RemoteException) {
                Log.w(TAG, "Helper protocol check failed", e)
                UNKNOWN_PROTOCOL
            }
        if (version != Registration.PROTOCOL_VERSION) {
            Log.w(
                TAG,
                "Rejecting helper protocol $version; expected ${Registration.PROTOCOL_VERSION}",
            )
            abandon(proxy)
            return false
        }
        val accepted = accept(newBinder, proxy)
        if (!accepted) onRejected?.invoke()
        return accepted
    }

    private fun stopPrevious(proxy: IHelper) {
        Log.i(TAG, "Stopping the previous helper")
        try {
            proxy.shutdown()
        } catch (e: RemoteException) {
            Log.i(TAG, "Previous helper stopped", e)
        }
    }

    private fun abandon(proxy: IHelper) {
        try {
            proxy.shutdown()
        } catch (e: RemoteException) {
            Log.w(TAG, "Could not stop the stale helper", e)
        }
        onRejected?.invoke()
    }

    private fun accept(newBinder: IBinder, proxy: IHelper): Boolean {
        val previous = binder
        val replaced = previous != null && previous != newBinder
        if (previous != newBinder) {
            try {
                newBinder.linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                Log.w(TAG, "Helper died before registration", e)
                return false
            }
        }
        if (replaced) {
            runCatching { previous.unlinkToDeath(deathRecipient, 0) }
            // The old process may still hold UiAutomation. A second one cannot register until it
            // exits, and global buttons then fail.
            helper?.let { stopPrevious(it) }
        }
        val first = helper == null
        binder = newBinder
        helper = proxy
        if (first) {
            recoveredAtMs = System.currentTimeMillis()
            val pid = runCatching { proxy.pid() }.getOrDefault(-1)
            Log.i(TAG, "Helper registered (pid $pid, protocol ${Registration.PROTOCOL_VERSION})")
        }
        setStatus(HelperStatus.RUNNING)
        onReady?.invoke(proxy)
        readyListeners.forEach { it(proxy) }
        return true
    }

    @Synchronized
    fun setStatus(next: HelperStatus) {
        // A supervisor pass can decide the helper is idle while registration is in flight.
        if (helper != null && next != HelperStatus.RUNNING) return
        if (statusFlow.value == next) return
        statusFlow.value = next
        bus.emit(EventTypes.HELPER_STATUS, HelperStatusData(next))
    }

    private companion object {
        const val TAG = "FingertipsHelper"
        const val UNKNOWN_PROTOCOL = -1
    }
}
