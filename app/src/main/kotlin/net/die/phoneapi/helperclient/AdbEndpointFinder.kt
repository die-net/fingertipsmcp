package net.die.phoneapi.helperclient

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds this device's wireless-debugging ports. The helper asks the adb service, the same answer
 * Settings shows. mDNS is for when the helper is down: its record can end while adbd keeps
 * listening, since NsdService stops mdnsd 10 s after a search and adbd doesn't register again until
 * wireless debugging is toggled.
 */
internal class AdbEndpointFinder(
    private val context: Context,
    private val helper: HelperConnection,
    private val io: CoroutineDispatcher,
) {
    suspend fun connectPort(timeoutMs: Long = CONNECT_WAIT_MS): Int? {
        helperPort()
            ?.takeIf { portOpen(it) }
            ?.let {
                return it
            }
        // Android 11 resolves only one service at a time, and the first record can be a port
        // adbd has already closed. Walk every advertised service and keep the one that accepts
        // a connection.
        return withTimeoutOrNull(timeoutMs) { resolveOpenPort(TLS_CONNECT) }
    }

    suspend fun pairingPort(timeoutMs: Long = PAIR_WAIT_MS): Int? =
        withContext(io) {
            KadbMdnsAndroid(context).use { mdns ->
                mdns.start()
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    val devices = mdns.state.value.pairDevices
                    val port = devices.firstOrNull { portOpen(it.port) }?.port
                    if (port != null) return@withContext port
                    delay(POLL_MS)
                }
                null
            }
        }

    @Suppress("DEPRECATION") // resolveService is the one-at-a-time API this Android version has.
    private suspend fun resolveOpenPort(type: String): Int? = suspendCancellableCoroutine { cont ->
        val nsd = context.getSystemService(NsdManager::class.java)
        if (nsd == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val main = Handler(Looper.getMainLooper())
        val pending = ArrayDeque<NsdServiceInfo>()
        val seen = HashSet<String>()
        val finished = AtomicBoolean()
        var resolving = false
        var discovery: NsdManager.DiscoveryListener? = null

        fun stop() {
            discovery?.let { listener ->
                discovery = null
                runCatching { nsd.stopServiceDiscovery(listener) }
            }
        }

        fun finish(port: Int?) {
            if (!finished.compareAndSet(false, true)) return
            stop()
            cont.resume(port)
        }

        fun pump() {
            if (!cont.isActive || resolving) return
            val next = pending.removeFirstOrNull() ?: return
            resolving = true
            nsd.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Could not resolve ${serviceInfo.serviceName}: $errorCode")
                        resolving = false
                        main.post { pump() }
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val port = resolved.port
                        Thread {
                            val open = portOpen(port)
                            main.post {
                                if (open) {
                                    Log.i(TAG, "Wireless debugging is listening on $port")
                                    finish(port)
                                } else {
                                    Log.w(TAG, "Skipping closed wireless debugging port $port")
                                    resolving = false
                                    pump()
                                }
                            }
                        }
                            .start()
                    }
                },
            )
        }

        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "Wireless debugging discovery failed: $errorCode")
                    finish(null)
                }

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (seen.add(serviceInfo.serviceName)) pending.add(serviceInfo)
                    pump()
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    pending.removeAll { it.serviceName == serviceInfo.serviceName }
                }
            }
        discovery = listener
        cont.invokeOnCancellation { main.post { stop() } }
        main.post {
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { error ->
                    Log.w(TAG, "Could not browse wireless debugging", error)
                    finish(null)
                }
        }
    }

    @Suppress("SwallowedException") // A closed port is not this phone's wireless-debugging server.
    private fun portOpen(port: Int): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS)
            }
            true
        } catch (e: IOException) {
            false
        }

    private suspend fun helperPort(): Int? {
        val proxy = helper.getOrNull() ?: return null
        return withContext(io) {
            try {
                validAdbPort(proxy.adbWirelessPort())
            } catch (e: RemoteException) {
                Log.w(TAG, "The helper could not read the wireless debugging port", e)
                null
            }
        }
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val TLS_CONNECT = "_adb-tls-connect._tcp."
        const val LOOPBACK = "127.0.0.1"
        const val CONNECT_WAIT_MS = 15_000L
        const val PAIR_WAIT_MS = 8_000L
        const val POLL_MS = 300L
        const val CONNECT_TIMEOUT_MS = 200
    }
}
