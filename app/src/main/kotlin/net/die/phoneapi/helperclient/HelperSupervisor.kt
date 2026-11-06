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
import java.net.ConnectException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.die.phoneapi.model.HelperStatus
import net.die.phoneapi.server.NetworkWatcher

/**
 * Brings the helper back after a crash or a package update once wireless debugging is paired.
 *
 * The same wireless debugging session is what reaches Chrome DevTools, so a successful launch
 * leaves it on. Below Android 11 the helper is started over USB.
 *
 * This is the Android side. [RestartLoop] decides when to try, and [WirelessSetting] owns the
 * setting.
 */
internal class HelperSupervisor(
    private val context: Context,
    private val helper: HelperConnection,
    private val keys: KeystorePrivateKeyStore,
    private val finder: AdbEndpointFinder,
    private val network: NetworkWatcher,
    private val setting: WirelessSetting,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val secure = SecureSettings(context.contentResolver)
    private val launcher =
        object : HelperLauncher {
            override val running: Boolean
                get() = helper.isRunning

            override val paired: Boolean
                get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && keys.isPaired

            override val canWriteSetting: Boolean
                get() = secure.canWrite(context)

            override suspend fun connectPort(): Int? = finder.connectPort()

            override suspend fun launch(port: Int): Boolean {
                if (!launchHelper(port) || !waitUntilRunning()) return false
                keys.markPaired()
                return true
            }

            override fun showStarting() = helper.setStatus(HelperStatus.STARTING)

            override fun showIdle() = helper.setStatus(idleStatus())

            override fun shutdown() = helper.shutdown()
        }
    private val loop = RestartLoop(launcher, setting)
    private val wirelessObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                setting.refresh()
            }
        }

    /** True while Settings has wireless debugging turned on. */
    val wireless: StateFlow<Boolean> = setting.on

    fun start() {
        KadbCert.configure(
            store = keys,
            policy = KadbCertPolicy(autoHealInvalidPrivateKey = false),
        )
        // Only turning it on is worth a fresh start. Android turns it off on a network it hasn't
        // approved, and starting over then would put its prompt back on screen each time.
        setting.onOutsideChange = { on -> if (on) loop.wake(Wake.FRESH) }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(ADB_WIFI_ENABLED),
            false,
            wirelessObserver,
        )
        setting.refresh()
        helper.onDied = { loop.wake(Wake.FRESH) }
        helper.onRejected = { loop.wake(Wake.RETRY) }
        scope.coroutineContext[Job]?.invokeOnCompletion {
            context.contentResolver.unregisterContentObserver(wirelessObserver)
        }
        scope.launch { network.lanAddress.drop(1).collect { loop.wake(Wake.FRESH) } }
        scope.launch { loop.run() }
        loop.wake(Wake.RETRY)
    }

    /** User-driven start. Forgets earlier failures and clears a user stop. */
    fun nudge() = loop.wake(Wake.RESUME)

    /** User-driven stop. The process exits, and nothing starts it again until [nudge]. */
    fun stop() = loop.wake(Wake.STOP)

    fun isPaired(): Boolean = keys.isPaired

    /** The phone can launch the helper over wireless debugging. */
    fun canStartHelper(): Boolean =
        launcher.paired && (setting.on.value || launcher.canWriteSetting)

    fun canControlWireless(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && secure.canWrite(context)

    /** Sets wireless debugging as the user asked. Turning it on is a reason to try the helper. */
    fun setWirelessEnabled(on: Boolean) {
        setting.setByUser(on)
        if (on) loop.wake(Wake.FRESH)
    }

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

    private suspend fun pairWith(code: String, port: Int) {
        val hosts = buildList {
            add("127.0.0.1")
            val lan = network.lanAddress.value?.hostAddress
            if (lan != null && lan != "127.0.0.1") add(lan)
        }
        pairAtFirstListening(hosts) { host ->
            withContext(ioDispatcher) { Kadb.pair(host, port, code) }
        }
    }

    private fun idleStatus(): HelperStatus =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HelperStatus.NEEDS_PAIRING
        } else {
            HelperStatus.NEEDS_USB
        }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // Kadb reports auth and socket failures as assorted exceptions.
    private suspend fun launchHelper(port: Int): Boolean =
        withContext(ioDispatcher) {
            try {
                Kadb.create("127.0.0.1", port).use { kadb ->
                    // The serial names this phone's mDNS records, so a later search resolves only
                    // those.
                    finder.connected(kadb.shell(READ_SERIAL).output.trim(), port)
                    // The helper allows one process per package, so this is a no-op when this
                    // package's helper is already up, and starts one beside another package's.
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
                finder.refused(port)
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
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        const val RUNNING_WAIT_MS = 10_000L
        const val POLL_MS = 200L
        val PAIRING_CODE = Regex("""\d{6}""")
        const val READ_SERIAL = "getprop ro.serialno"

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

/**
 * Pairs at the first of [hosts] where something is listening. Only a refused connection moves on to
 * the next host, since the pairing server may listen on one address and not another. Anything else,
 * such as a wrong code, would fail the same way at every address, and sending a code twice only
 * spends the dialog's attempts, so it is thrown at once.
 */
internal suspend fun pairAtFirstListening(hosts: List<String>, pair: suspend (String) -> Unit) {
    var refused: ConnectException? = null
    for (host in hosts) {
        try {
            pair(host)
            return
        } catch (e: ConnectException) {
            // Nothing listens there. The last refusal is thrown, and pair() logs it.
            refused = e
        }
    }
    throw refused ?: IllegalArgumentException("No address to pair with")
}
