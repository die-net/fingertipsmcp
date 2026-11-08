package com.fingertipsmcp.helperclient

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** A wireless-debugging record on the network, which has to be resolved for its host and port. */
internal interface AdbRecord {
    val name: String

    /** Asks for the record's address. [done] runs once, on another thread, or never if it hangs. */
    fun resolve(done: (ResolveOutcome) -> Unit)
}

internal sealed interface ResolveOutcome {
    data class Resolved(val host: InetAddress?, val port: Int) : ResolveOutcome

    /** Another resolve in this process has not finished, so this one was not started. */
    data object Busy : ResolveOutcome

    data object Failed : ResolveOutcome
}

/** What a browse hears, on another thread. */
internal interface RecordListener {
    fun found(record: AdbRecord)

    fun lost(name: String)

    /** The browse could not start, so nothing more is coming. */
    fun failed()
}

/** Lists the records of one mDNS service type. */
internal fun interface RecordBrowser {
    /** Starts browsing [type] for [listener], until the result is closed. */
    fun browse(type: String, listener: RecordListener): AutoCloseable
}

/**
 * Finds this phone's own wireless-debugging ports among the records on the network. Below Android
 * 14 NsdManager can't cancel a resolve, and one that never completes leaves it refusing every later
 * resolve in this process with FAILURE_ALREADY_ACTIVE. A record from another device can hang that
 * way. So every search in the app goes through here, resolves one record at a time, and with the
 * serial known resolves only records named for it. With it unknown every record is, until the first
 * one found to be this phone's records the serial.
 */
internal class AdbPortSearch(
    private val browser: RecordBrowser,
    private val identity: AdbIdentity,
    private val io: CoroutineDispatcher,
) {
    /**
     * This phone's port for [type] as it comes and goes: a port when a record on this device
     * resolves to one [accept] takes, and null when the last such record is lost. Ends only if the
     * browse fails.
     */
    fun ports(type: String, accept: (Int) -> Boolean = { true }): Flow<Int?> = channelFlow {
        val events = Channel<WalkEvent>(Channel.UNLIMITED)
        val walk = PortWalk(identity, accept, events, this)
        browser.browse(type, EventListener(events)).use {
            for (event in events) if (!walk.handle(event)) break
        }
    }
        .distinctUntilChanged()
        .flowOn(io)
}

private sealed interface WalkEvent {
    data class Found(val record: AdbRecord) : WalkEvent

    data class Lost(val name: String) : WalkEvent

    data class Answered(val record: AdbRecord, val outcome: ResolveOutcome) : WalkEvent

    data class Retry(val record: AdbRecord) : WalkEvent

    data object BrowseFailed : WalkEvent
}

private class EventListener(private val events: SendChannel<WalkEvent>) : RecordListener {
    override fun found(record: AdbRecord) {
        events.trySend(WalkEvent.Found(record))
    }

    override fun lost(name: String) {
        events.trySend(WalkEvent.Lost(name))
    }

    override fun failed() {
        events.trySend(WalkEvent.BrowseFailed)
    }
}

/** One search's records, all handled by the coroutine reading its events, so none is shared. */
private class PortWalk(
    private val identity: AdbIdentity,
    private val accept: (Int) -> Boolean,
    private val events: SendChannel<WalkEvent>,
    private val out: ProducerScope<Int?>,
) {
    private var serial = identity.serial
    private val queue = ResolveQueue<AdbRecord>(::wanted)
    private val ports = LinkedHashMap<String, Int>()
    private var resolving = false
    private var saidBusy = false

    init {
        if (serial == null) Log.i(TAG, "This phone's adb serial is not known yet")
    }

    private fun wanted(name: String): Boolean = serial?.let { isOwnAdbRecord(name, it) } != false

    /** Handles [event], and says whether the search goes on. */
    suspend fun handle(event: WalkEvent): Boolean {
        when (event) {
            is WalkEvent.Found -> queue.found(event.record.name, event.record)
            is WalkEvent.Lost -> lost(event.name)
            is WalkEvent.Answered -> answered(event.record, event.outcome)
            is WalkEvent.Retry -> retry(event.record)
            WalkEvent.BrowseFailed -> return false
        }
        pump()
        return true
    }

    /** Starts the next wanted record, which a serial learned since it was queued can rule out. */
    private fun pump() {
        if (resolving) return
        var next = queue.next()
        while (next != null && !wanted(next.name)) next = queue.next()
        if (next == null) return
        resolving = true
        resolve(next)
    }

    private fun resolve(record: AdbRecord) {
        record.resolve { events.trySend(WalkEvent.Answered(record, it)) }
    }

    private suspend fun lost(name: String) {
        queue.lost(name)
        if (ports.remove(name) != null) out.send(ports.values.firstOrNull())
    }

    private suspend fun answered(record: AdbRecord, outcome: ResolveOutcome) {
        when (outcome) {
            is ResolveOutcome.Resolved -> {
                resolving = false
                if (queue.advertised(record.name)) resolved(record.name, outcome)
            }
            ResolveOutcome.Failed -> resolving = false
            ResolveOutcome.Busy -> later(record)
        }
    }

    /**
     * Tries [record] again after a wait, holding its turn. Another search's resolve clears in
     * milliseconds. One that hung never does, and this keeps trying until the search ends.
     */
    private fun later(record: AdbRecord) {
        if (!saidBusy) Log.w(TAG, "Another wireless debugging resolve is still running")
        saidBusy = true
        out.launch {
            delay(BUSY_RETRY_MS)
            events.trySend(WalkEvent.Retry(record))
        }
    }

    private fun retry(record: AdbRecord) {
        if (queue.advertised(record.name)) resolve(record) else resolving = false
    }

    private suspend fun resolved(name: String, record: ResolveOutcome.Resolved) {
        val port = record.port
        val local = record.host != null && record.host in localAddresses()
        if (local && accept(port)) {
            Log.i(TAG, "Wireless debugging is listening on $port")
            learnSerial(name)
            ports[name] = port
            out.send(ports.values.first())
        } else {
            val why = if (local) "closed" else "another device's"
            Log.w(TAG, "Skipping $why wireless debugging port $port")
        }
    }

    private fun learnSerial(name: String) {
        if (serial != null) return
        serialOfAdbRecord(name)?.let(identity::rememberSerial)
        serial = identity.serial
    }

    private companion object {
        const val TAG = "FingertipsHelper"
        const val BUSY_RETRY_MS = 300L
    }
}

/** [RecordBrowser] on Android's NsdManager. */
internal class NsdRecordBrowser(private val context: Context) : RecordBrowser {
    override fun browse(type: String, listener: RecordListener): AutoCloseable {
        val nsd = context.getSystemService(NsdManager::class.java)
        if (nsd == null) {
            listener.failed()
            return AutoCloseable {}
        }
        val discovery = Discovery(nsd, listener)
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { error ->
                Log.w(TAG, "Could not browse wireless debugging", error)
                listener.failed()
            }
        return AutoCloseable { runCatching { nsd.stopServiceDiscovery(discovery) } }
    }

    private class Discovery(private val nsd: NsdManager, private val listener: RecordListener) :
        NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Wireless debugging discovery failed: $errorCode")
            listener.failed()
        }

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            listener.found(NsdRecord(nsd, serviceInfo))
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            listener.lost(serviceInfo.serviceName)
        }
    }

    private class NsdRecord(private val nsd: NsdManager, private val info: NsdServiceInfo) :
        AdbRecord {
        override val name: String = info.serviceName

        @Suppress("DEPRECATION") // resolveService is the one API every supported Android has.
        override fun resolve(done: (ResolveOutcome) -> Unit) {
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                            done(ResolveOutcome.Busy)
                        } else {
                            Log.w(TAG, "Could not resolve a wireless debugging record: $errorCode")
                            done(ResolveOutcome.Failed)
                        }
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        done(ResolveOutcome.Resolved(resolved.host, resolved.port))
                    }
                },
            )
        }
    }

    private companion object {
        const val TAG = "FingertipsHelper"
    }
}
