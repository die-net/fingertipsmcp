package net.die.phoneapi.helperclient

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.exception.AdbAuthException
import com.flyfishxu.kadb.exception.AdbPairAuthException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.die.phoneapi.model.HelperStatus
import net.die.phoneapi.server.NetworkWatcher

/**
 * Brings the helper back after a crash or a package update once wireless debugging is paired.
 *
 * The same wireless debugging session is what reaches Chrome DevTools, so a successful launch
 * leaves it on. Below Android 11 the helper is started over USB.
 */
internal class HelperSupervisor(
    private val context: Context,
    private val helper: HelperConnection,
    private val keys: KeystorePrivateKeyStore,
    private val network: NetworkWatcher,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val gate = Mutex()
    private val finder = AdbEndpointFinder(context, ioDispatcher)
    private val secure = SecureSettings(context.contentResolver)
    private var failures = 0

    /** The user turned the helper off. Automatic restarts wait until the next [nudge]. */
    @Volatile private var holdOff = false
    private val wirelessState = MutableStateFlow(wirelessEnabled())
    private val wirelessObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                wirelessState.value = wirelessEnabled()
                scope.launch { runAttempt(resetFailures = true) }
            }
        }

    /** True while Settings has wireless debugging turned on. */
    val wireless: StateFlow<Boolean> = wirelessState.asStateFlow()

    fun start() {
        KadbCert.configure(
            store = keys,
            policy = KadbCertPolicy(autoHealInvalidPrivateKey = false),
        )
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(ADB_WIFI_ENABLED),
            false,
            wirelessObserver,
        )
        wirelessState.value = wirelessEnabled()
        helper.onDied = { scope.launch { runAttempt() } }
        helper.onRejected = { scope.launch { runAttempt() } }
        scope.coroutineContext[Job]?.invokeOnCompletion {
            context.contentResolver.unregisterContentObserver(wirelessObserver)
        }
        scope.launch {
            network.lanAddress.drop(1).collect { runAttempt(resetFailures = true) }
        }
        scope.launch { runAttempt() }
    }

    /** User-driven start. Resets the backoff counter and clears a user stop. */
    fun nudge() {
        scope.launch { runAttempt(resetFailures = true, resume = true) }
    }

    /** User-driven stop. The process exits, and nothing starts it again until [nudge]. */
    fun stop() {
        holdOff = true
        scope.launch { gate.withLock { helper.shutdown() } }
    }

    fun isPaired(): Boolean = keys.isPaired

    /** The phone can launch the helper over wireless debugging. */
    fun canStartHelper(): Boolean = wirelessReady()

    fun canControlWireless(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && secure.canWrite(context)

    fun setWirelessEnabled(on: Boolean) {
        secure.setWirelessEnabled(on)
        wirelessState.value = wirelessEnabled()
    }

    fun wirelessEnabled(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && secure.wirelessEnabled()

    /** Pairs with the wireless-debugging dialog, then starts the helper. */
    @Suppress(
        "TooGenericExceptionCaught"
    ) // Pairing failures arrive as library-specific exceptions.
    suspend fun pair(code: String, port: Int?): PairOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return PairOutcome(false, "Wireless debugging needs Android 11 or newer.")
        }
        if (!PAIRING_CODE.matches(code)) return PairOutcome(false, "Enter the 6-digit code.")
        val endpoint =
            port
                ?: finder.pairingPort()
                ?: return PairOutcome(
                    false,
                    "Could not find the pairing dialog. Open it and try again.",
                )
        return try {
            pairWith(code, endpoint)
            keys.markPaired()
            nudge()
            PairOutcome(true, "Paired. Starting the helper.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Wireless debugging pairing failed", e)
            PairOutcome(false, pairingFailure(e))
        }
    }

    data class PairOutcome(val paired: Boolean, val message: String)

    @Suppress(
        "TooGenericExceptionCaught"
    ) // Kadb reports refused and auth failures as assorted exceptions.
    private suspend fun pairWith(code: String, port: Int) {
        val hosts = buildList {
            add("127.0.0.1")
            val lan = network.lanAddress.value?.hostAddress
            if (lan != null && lan != "127.0.0.1") add(lan)
        }
        var failure: Exception? = null
        for (host in hosts) {
            try {
                withContext(ioDispatcher) { Kadb.pair(host, port, code) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                Log.w(TAG, "Pairing $host:$port failed", e)
            }
        }
        throw failure ?: error("Wireless debugging pairing failed")
    }

    private suspend fun runAttempt(resetFailures: Boolean = false, resume: Boolean = false) {
        var reset = resetFailures
        var allow = resume
        while (true) {
            val delayMs =
                gate.withLock {
                    if (allow) {
                        holdOff = false
                        allow = false
                    }
                    if (reset) {
                        failures = 0
                        reset = false
                    }
                    doAttempt()
                } ?: return
            delay(delayMs)
        }
    }

    private suspend fun doAttempt(): Long? {
        if (holdOff || helper.isRunning) return null
        if (!wirelessReady()) {
            helper.setStatus(idleStatus())
            return null
        }
        helper.setStatus(HelperStatus.STARTING)
        if (restartOverWireless()) return null
        if (helper.isRunning) return null
        failures += 1
        if (failures >= MAX_FAILURES) {
            helper.setStatus(idleStatus())
            return null
        }
        return restartDelayMs(failures - 1)
    }

    private fun idleStatus(): HelperStatus =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HelperStatus.NEEDS_PAIRING
        } else {
            HelperStatus.NEEDS_USB
        }

    private fun wirelessReady(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !keys.isPaired) return false
        return secure.wirelessEnabled() || secure.canWrite(context)
    }

    private suspend fun restartOverWireless(): Boolean {
        val wasOn = secure.wirelessEnabled()
        var started = false
        return try {
            if (!wasOn) {
                secure.setWirelessEnabled(true)
                wirelessState.value = true
            }
            val port = finder.connectPort()
            if (port == null) {
                Log.w(TAG, "Wireless debugging did not publish a TLS port")
                false
            } else if (launchHelper(port) && waitUntilRunning()) {
                keys.markPaired()
                started = true
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not toggle wireless debugging", e)
            false
        } finally {
            // A failed launch puts the setting back. A running helper keeps wireless debugging on
            // so adbd can open Chrome's DevTools socket.
            if (!started && !wasOn && secure.canWrite(context)) restoreWirelessOff()
        }
    }

    private fun restoreWirelessOff() {
        try {
            secure.setWirelessEnabled(false)
            wirelessState.value = false
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not turn wireless debugging back off", e)
        }
    }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // Kadb reports auth and socket failures as assorted exceptions.
    private suspend fun launchHelper(port: Int): Boolean =
        withContext(ioDispatcher) {
            try {
                Kadb.create("127.0.0.1", port).use { kadb ->
                    val listed = kadb.shell(LIST_CMDLINES)
                    if (HELPER_MAIN in listed.output) {
                        Log.i(TAG, "Helper process is already running")
                        return@withContext true
                    }
                    val launched = kadb.shell(HelperLaunch.deviceCommand(context.packageName))
                    if (launched.exitCode != 0) {
                        Log.w(
                            TAG,
                            "Helper launch exited ${launched.exitCode}: ${launched.allOutput}",
                        )
                        false
                    } else {
                        true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not launch the helper over wireless debugging", e)
                false
            }
        }

    private suspend fun waitUntilRunning(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + RUNNING_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (helper.isRunning) return true
            delay(POLL_MS)
        }
        return helper.isRunning
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val HELPER_MAIN = "net.die.phoneapi.helper.Main"
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        const val MAX_FAILURES = 6
        const val RUNNING_WAIT_MS = 10_000L
        const val POLL_MS = 200L
        val PAIRING_CODE = Regex("""\d{6}""")
        const val LIST_CMDLINES =
            """for c in /proc/[0-9]*/cmdline; do tr '\0' ' ' < "${'$'}c" 2>/dev/null; echo; done"""

        fun pairingFailure(error: Exception): String =
            if (error is AdbAuthException || error is AdbPairAuthException) {
                "Wireless debugging rejected the pairing. Open the pairing dialog again and " +
                    "enter the new 6-digit code."
            } else {
                val detail = error.message?.takeIf { it.isNotBlank() }?.lineSequence()?.first()
                if (detail == null) {
                    "Pairing failed. Open the wireless debugging dialog again and enter the code."
                } else {
                    "Pairing failed ($detail). Open the wireless debugging dialog again and " +
                        "enter the code."
                }
            }
    }
}
