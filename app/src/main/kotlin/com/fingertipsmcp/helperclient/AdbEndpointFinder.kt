package com.fingertipsmcp.helperclient

import android.os.RemoteException
import android.util.Log
import com.fingertipsmcp.helper.IHelper
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds this device's wireless-debugging ports. The helper asks the adb service, the same answer
 * Settings shows. Without the helper, the last port that took a connection is tried, then mDNS: its
 * record can end while adbd keeps listening, since NsdService stops mdnsd 10 s after a search and
 * adbd doesn't register again until wireless debugging is toggled.
 */
internal class AdbEndpointFinder(
    private val helper: HelperConnection,
    private val io: CoroutineDispatcher,
    private val identity: AdbIdentity,
    browser: RecordBrowser,
) {
    private val search = AdbPortSearch(browser, identity, io)

    init {
        helper.addReadyListener(::learnSerial)
    }

    /**
     * Reads `ro.serialno` through a helper that just registered, so a phone whose helper was
     * started over USB knows which mDNS records are its own before it ever needs a search.
     */
    @Suppress("TooGenericExceptionCaught") // Binder rethrows the helper's runtime exceptions.
    private fun learnSerial(proxy: IHelper) {
        val result =
            try {
                proxy.exec(READ_SERIAL, SERIAL_TIMEOUT_MS, SERIAL_MAX_BYTES)
            } catch (e: RemoteException) {
                Log.w(TAG, "The helper could not read the serial", e)
                return
            } catch (e: RuntimeException) {
                // Without it the next search resolves every record, as before.
                Log.w(TAG, "The helper could not read the serial", e)
                return
            } ?: return
        identity.recordSerial(result.exitCode, result.stdout.orEmpty())
    }

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
        return withTimeoutOrNull(timeoutMs) { firstOpenPort(TLS_CONNECT) }
    }

    /** Records that adbd on [port], reporting [serial] as `ro.serialno`, took a connection. */
    fun connected(serial: String?, port: Int) {
        identity.remember(serial, port)
    }

    /** Records that [port] did not take a connection, so it is not tried first again. */
    fun refused(port: Int) {
        identity.forgetPort(port)
    }

    /** The port of the pairing dialog open on this phone, if one shows up within [timeoutMs]. */
    suspend fun pairingPort(timeoutMs: Long = PAIR_WAIT_MS): Int? =
        withTimeoutOrNull(timeoutMs) { firstOpenPort(TLS_PAIRING) }

    /** This phone's pairing port as dialogs open and close, null while none is open. */
    fun pairingPorts(): Flow<Int?> = search.ports(TLS_PAIRING)

    private suspend fun firstOpenPort(type: String): Int? =
        search.ports(type, ::portOpen).filterNotNull().firstOrNull()

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
        const val TAG = "FingertipsHelper"
        const val TLS_CONNECT = "_adb-tls-connect._tcp."
        const val TLS_PAIRING = "_adb-tls-pairing._tcp."
        const val LOOPBACK = "127.0.0.1"
        const val CONNECT_WAIT_MS = 15_000L
        const val PAIR_WAIT_MS = 8_000L
        const val CONNECT_TIMEOUT_MS = 200
        val READ_SERIAL = arrayOf("getprop", "ro.serialno")
        const val SERIAL_TIMEOUT_MS = 5_000L
        const val SERIAL_MAX_BYTES = 256
    }
}
