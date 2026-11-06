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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds this device's wireless-debugging ports. The helper asks the adb service, the same answer
 * Settings shows. Without the helper, the last port that took a connection is tried, then mDNS: its
 * record can end while adbd keeps listening, since NsdService stops mdnsd 10 s after a search and
 * adbd doesn't register again until wireless debugging is toggled.
 */
internal class AdbEndpointFinder(
    private val context: Context,
    private val helper: HelperConnection,
    private val io: CoroutineDispatcher,
    private val identity: AdbIdentity = AdbIdentity(context.filesDir),
) {
    suspend fun connectPort(timeoutMs: Long = CONNECT_WAIT_MS): Int? {
        helperPort()
            ?.takeIf { portOpen(it) }
            ?.let {
                return it
            }
        // adbd keeps its port while wireless debugging stays on. Trying it first also gets past
        // an NsdManager that a hung resolve has left refusing every other one.
        withContext(io) { identity.lastPort?.takeIf { portOpen(it) } }
            ?.let {
                Log.i(TAG, "Trying the last wireless debugging port")
                return it
            }
        // Android 11 resolves only one service at a time, and the first record can be a port
        // adbd has already closed. Walk this phone's advertised services and keep the one that
        // accepts a connection.
        return withTimeoutOrNull(timeoutMs) { resolveOpenPort(TLS_CONNECT, identity.serial) }
    }

    /** Records that adbd on [port], reporting [serial] as `ro.serialno`, took a connection. */
    fun connected(serial: String?, port: Int) {
        identity.remember(serial, port)
    }

    /** Records that [port] did not take a connection, so it is not tried first again. */
    fun refused(port: Int) {
        identity.forgetPort(port)
    }

    suspend fun pairingPort(timeoutMs: Long = PAIR_WAIT_MS): Int? =
        withContext(io) {
            KadbMdnsAndroid(context).use { mdns ->
                mdns.start()
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    val own = localAddresses()
                    val found =
                        mdns.state.value.pairDevices.firstOrNull {
                            parseHost(it.host) in own && portOpen(it.port)
                        }
                    if (found != null) {
                        serialOfAdbRecord(found.name)?.let(identity::rememberSerial)
                        return@withContext found.port
                    }
                    delay(POLL_MS)
                }
                null
            }
        }

    /**
     * Resolves the [type] records this phone's adbd published, one at a time, and returns the first
     * port that is on this device and accepts a connection. Below Android 14 NsdManager can't
     * cancel a resolve, and one that never completes leaves it refusing every later resolve in this
     * process with FAILURE_ALREADY_ACTIVE. A record from another device can hang that way, so with
     * [serial] known only records named for it are resolved. With it unknown every record is, and
     * the first one found to be this phone's records the serial.
     */
    @Suppress("DEPRECATION") // resolveService is the one-at-a-time API this Android version has.
    private suspend fun resolveOpenPort(type: String, serial: String?): Int? =
        suspendCancellableCoroutine { cont ->
            val nsd = context.getSystemService(NsdManager::class.java)
            if (nsd == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            if (serial == null) Log.i(TAG, "This phone's adb serial is not known yet")
            val main = Handler(Looper.getMainLooper())
            val queue =
                ResolveQueue<NsdServiceInfo> { name ->
                    serial == null || isOwnAdbRecord(name, serial)
                }
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
                val next = queue.next() ?: return
                resolving = true
                nsd.resolveService(
                    next,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "Could not resolve a wireless debugging record: $errorCode")
                            resolving = false
                            main.post { pump() }
                        }

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val name = resolved.serviceName
                            val host = resolved.host
                            val port = resolved.port
                            Thread {
                                val local = host != null && host in localAddresses()
                                val open = local && portOpen(port)
                                main.post {
                                    if (open) {
                                        Log.i(TAG, "Wireless debugging is listening on $port")
                                        if (serial == null) {
                                            serialOfAdbRecord(name)?.let(identity::rememberSerial)
                                        }
                                        finish(port)
                                    } else {
                                        val why = if (local) "closed" else "another device's"
                                        Log.w(TAG, "Skipping $why wireless debugging port $port")
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
                        queue.found(serviceInfo.serviceName, serviceInfo)
                        pump()
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        queue.lost(serviceInfo.serviceName)
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

    private fun parseHost(host: String): InetAddress? =
        try {
            InetAddress.getByName(host)
        } catch (e: UnknownHostException) {
            Log.w(TAG, "Unparseable mDNS host", e)
            null
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
