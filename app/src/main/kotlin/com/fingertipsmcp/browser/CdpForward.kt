package com.fingertipsmcp.browser

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.fingertipsmcp.core.openOn
import com.fingertipsmcp.helperclient.AdbEndpointFinder
import com.fingertipsmcp.helperclient.KeystorePrivateKeyStore
import com.fingertipsmcp.helperclient.SecureSettings
import com.fingertipsmcp.helperclient.WirelessSetting
import com.fingertipsmcp.model.ApiException
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.stream.AdbStream
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Opens Chrome's DevTools socket by asking adbd to connect. The helper runs as shell and cannot
 * `connectto` an app socket; adbd can, and Chrome accepts adbd's uid.
 *
 * One wireless-debugging connection is shared by every open socket. It stays up until nothing has
 * used it for [IDLE_MS], the same idle window as UiAutomation.
 */
internal class CdpForward(
    private val context: Context,
    private val keys: KeystorePrivateKeyStore,
    private val finder: AdbEndpointFinder,
    private val wireless: WirelessSetting,
    private val io: CoroutineDispatcher,
) {
    private val secure = SecureSettings(context.contentResolver)
    private val idle = CoroutineScope(SupervisorJob() + io)
    private val state = Any()
    private val wire = Any()
    private var client: Kadb? = null
    private var openStreams = 0
    private var lastUseMs = 0L
    private var generation = 0

    /**
     * Opens Chrome's abstract socket [name]. A socket that opens after the caller was cancelled is
     * closed, so its adb stream does not count as in use and keep the connection up forever.
     */
    @Suppress("MissingUseCall") // The caller closes the socket. This connection closes when idle.
    suspend fun open(name: String, answerTimeoutMs: Long = ANSWER_TIMEOUT_MS): DevtoolsSocket =
        openOn(io) { openStream(name, answerTimeoutMs, retry = true) }

    @Suppress("MissingUseCall") // closeStream closes the adb stream when the caller finishes.
    private suspend fun openStream(
        name: String,
        answerTimeoutMs: Long,
        retry: Boolean,
    ): DevtoolsSocket {
        val session = session()
        try {
            val stream = synchronized(wire) { session.client.open("localabstract:$name") }
            acquired()
            return socket(stream, answerTimeoutMs)
        } catch (e: IOException) {
            if (retry && !session.fresh && drop(session.client)) {
                return openStream(name, answerTimeoutMs, retry = false)
            }
            if (session.fresh) armIdle()
            throw connectFailed(name, e)
        }
    }

    @Suppress("MissingUseCall") // The DevTools socket owns these streams.
    private fun socket(stream: AdbStream, answerTimeoutMs: Long): DevtoolsSocket =
        AdbDevtoolsSocket(
            inputStream = stream.source.inputStream(),
            outputStream = stream.sink.outputStream(),
            release = { closeStream(stream) },
            answerTimeoutMs = answerTimeoutMs,
            dispatcher = io,
        )

    private fun closeStream(stream: AdbStream) {
        try {
            synchronized(wire) { stream.close() }
        } catch (_: IOException) {
            // The DevTools call is already finished.
        } finally {
            released()
        }
    }

    @Suppress("MissingUseCall") // The shared client closes after it has been idle.
    private suspend fun session(): Session {
        synchronized(state) { client }
            ?.let {
                return Session(it, fresh = false)
            }
        val port = connectPort()
        val created =
            try {
                Kadb.create(HOST, port)
            } catch (e: IOException) {
                throw connectFailed(HOST, e)
            }
        val adopted =
            synchronized(state) {
                if (client == null) {
                    client = created
                    true
                } else {
                    false
                }
            }
        if (!adopted) {
            created.close()
            return session()
        }
        Log.i(TAG, "Wireless debugging connected")
        return Session(created, fresh = true)
    }

    /** Closes [stale] when it is still the shared connection and no socket is using it. */
    private fun drop(stale: Kadb): Boolean {
        val current =
            synchronized(state) {
                if (client !== stale || openStreams > 0) return false
                client = null
                generation++
                stale
            }
        synchronized(wire) { current.close() }
        return true
    }

    private fun acquired() {
        synchronized(state) {
            openStreams++
            lastUseMs = SystemClock.uptimeMillis()
            generation++
        }
    }

    private fun released() {
        val gen =
            synchronized(state) {
                openStreams = (openStreams - 1).coerceAtLeast(0)
                lastUseMs = SystemClock.uptimeMillis()
                generation++
                if (openStreams > 0) return
                generation
            }
        scheduleIdle(gen)
    }

    private fun armIdle() {
        val gen =
            synchronized(state) {
                if (client == null || openStreams > 0) return
                lastUseMs = SystemClock.uptimeMillis()
                generation++
                generation
            }
        scheduleIdle(gen)
    }

    private fun scheduleIdle(gen: Int) {
        idle.launch {
            delay(IDLE_MS)
            disconnectIfIdle(gen)
        }
    }

    private fun disconnectIfIdle(gen: Int) {
        val current =
            synchronized(state) {
                val idleFor = SystemClock.uptimeMillis() - lastUseMs >= IDLE_MS
                if (generation != gen || openStreams > 0 || !idleFor) return
                val connected = client ?: return
                client = null
                connected
            }
        synchronized(wire) { current.close() }
        Log.i(TAG, "Wireless debugging idle; disconnecting")
    }

    private suspend fun connectPort(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw ApiException.unavailable("cdp_error", usbTunnelMessage(CHROME_SOCKET))
        }
        if (!keys.isPaired) {
            throw ApiException.unavailable(
                "cdp_error",
                "FingertipsMCP has not paired with wireless debugging. Pair it in the app before " +
                    "using the browser.",
            )
        }
        if (!wireless.refresh()) {
            if (!secure.canWrite(context)) {
                throw ApiException.unavailable(
                    "cdp_error",
                    "Wireless debugging is off, and FingertipsMCP cannot turn it on. Grant " +
                        "WRITE_SECURE_SETTINGS, or turn on wireless debugging in Developer options.",
                )
            }
            wireless.set(true)
        }
        return finder.connectPort()
            ?: throw ApiException.unavailable(
                "cdp_error",
                "FingertipsMCP can't find wireless debugging's port. Turn wireless debugging off and " +
                    "on, check that the phone is on Wi-Fi, and answer any prompt to allow this " +
                    "network, then retry.",
            )
    }

    private data class Session(val client: Kadb, val fresh: Boolean)

    private companion object {
        const val TAG = "Fingertips"
        const val HOST = "127.0.0.1"
        const val ANSWER_TIMEOUT_MS = 15_000L
        const val IDLE_MS = 3 * 60 * 1000L

        fun connectFailed(name: String, error: Exception): ApiException =
            ApiException(502, "cdp_error", devtoolsConnectMessage(name, error), cause = error)
    }
}
