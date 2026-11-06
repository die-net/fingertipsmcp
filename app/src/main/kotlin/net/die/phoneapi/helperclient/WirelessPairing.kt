package net.die.phoneapi.helperclient

import android.R.drawable.stat_sys_warning
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Takes the wireless-debugging pairing code from a heads-up notification.
 *
 * Android closes the pairing port as soon as its "Pair device with pairing code" dialog goes away,
 * which includes switching to another app, so the code can't be typed into PhoneAPI itself. After
 * [start], the notification stays in the shade for the whole search, because mDNS can miss the
 * dialog and the code would then have nowhere to go. When mDNS does see this phone's pairing
 * service, the notification drops down over the dialog.
 */
internal class WirelessPairing(
    private val context: Context,
    private val supervisor: HelperSupervisor,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private var search: Job? = null
    @Volatile private var port: Int? = null

    @Synchronized
    fun start() {
        search?.cancel()
        search = scope.launch {
            try {
                withTimeoutOrNull(SEARCH_MS) { watch() }
            } finally {
                port = null
                manager().cancel(NOTIFICATION_ID)
            }
        }
    }

    suspend fun submit(code: String) {
        post(notification("Pairing…", reply = false))
        val outcome = supervisor.pair(code, port)
        if (!outcome.paired) {
            post(notification(outcome.message, reply = true))
            return
        }
        synchronized(this) { search }?.cancelAndJoin()
        post(notification(outcome.message, reply = false).setTimeoutAfter(DONE_SHOWN_MS))
    }

    private suspend fun watch() =
        withContext(io) {
            KadbMdnsAndroid(context).use { mdns ->
                mdns.start()
                mdns.state
                    .map { ownPort(it.pairDevices) }
                    .distinctUntilChanged()
                    .collect { found ->
                        port = found
                        val prompt = codePrompt(found)
                        // A new post rather than an update: on an Android 11 Moto, a silent
                        // notification updated to an alerting one stayed in the shade. Android's
                        // own code reads as if it should drop down, so the cause is unknown.
                        if (prompt.alert) manager().cancel(NOTIFICATION_ID)
                        post(prompt(prompt))
                    }
            }
        }

    /**
     * Other phones on the network may be pairing too; only this one's dialog counts. A miss here
     * doesn't stop pairing, since [HelperSupervisor.pair] looks for the port again.
     */
    private fun ownPort(found: List<MdnsEndpoint>): Int? {
        if (found.isEmpty()) return null
        val own = localAddresses()
        return found.firstOrNull { parse(it.host) in own }?.port
    }

    private fun notification(text: String, reply: Boolean): NotificationCompat.Builder {
        val builder =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(stat_sys_warning)
                .setContentTitle("Wireless debugging code")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_STATUS)
                .setGroup(GROUP)
                .setOnlyAlertOnce(true)
        if (reply) builder.addAction(replyAction())
        return builder
    }

    private fun prompt(prompt: CodePrompt): NotificationCompat.Builder {
        val builder = notification(prompt.text, reply = true)
        return if (prompt.alert) builder else builder.setSilent(true)
    }

    private fun replyAction(): NotificationCompat.Action {
        val input = RemoteInput.Builder(HelperStartReceiver.EXTRA_CODE).setLabel("Code").build()
        // RemoteInput results are written into the intent, so this pending intent cannot be
        // immutable.
        val reply =
            PendingIntent.getBroadcast(
                context,
                REPLY_REQUEST,
                Intent(context, HelperStartReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        return NotificationCompat.Action.Builder(stat_sys_warning, "Enter code", reply)
            .addRemoteInput(input)
            .build()
    }

    private fun post(builder: NotificationCompat.Builder) {
        if (!context.notificationsAllowed()) return
        ensureChannel()
        // notificationsAllowed() already checked POST_NOTIFICATIONS. Lint does not see that call.
        @SuppressLint("MissingPermission") manager().notify(NOTIFICATION_ID, builder.build())
    }

    private fun manager() = NotificationManagerCompat.from(context)

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        // High importance so it drops down over the Settings dialog instead of hiding in the shade.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Wireless debugging pairing",
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val CHANNEL = "wireless_pairing"
        // A group of its own keeps Android from folding this into a group with PhoneAPI's other
        // notifications, where the code field takes two expands to reach.
        const val GROUP = "wireless_pairing"
        // HelperStatusNotifier uses id 8. Sharing it turns this post into an update of that
        // notification, and its status changes replace or cancel this one.
        const val NOTIFICATION_ID = 9
        const val REPLY_REQUEST = 2
        const val SEARCH_MS = 5 * 60_000L
        const val DONE_SHOWN_MS = 5_000L

        fun parse(host: String): InetAddress? =
            try {
                InetAddress.getByName(host)
            } catch (e: UnknownHostException) {
                Log.w(TAG, "Unparseable mDNS host $host", e)
                null
            }
    }
}

/** What the code notification says, and whether it drops down over the screen. */
internal data class CodePrompt(val text: String, val alert: Boolean)

/**
 * The code notification for a search that has found [port], or null if it hasn't found this phone's
 * dialog. Both ask for the code. Only a found dialog alerts, since nothing else is on screen yet to
 * read the code from.
 */
internal fun codePrompt(port: Int?): CodePrompt =
    if (port == null) {
        CodePrompt("Open Pair device with pairing code, then type the 6-digit code.", alert = false)
    } else {
        CodePrompt("Type the 6-digit code from the pairing dialog.", alert = true)
    }
