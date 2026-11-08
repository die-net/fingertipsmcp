package com.fingertipsmcp.helperclient

import android.R.drawable.stat_sys_warning
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fingertipsmcp.model.HelperStatus
import com.fingertipsmcp.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Tells the person holding the phone when the helper has to be started from a computer. */
class HelperStatusNotifier(private val context: Context, private val helper: HelperConnection) {
    fun start(scope: CoroutineScope) {
        scope.launch { helper.status.collect { show(it) } }
    }

    private fun show(status: HelperStatus) {
        val manager = NotificationManagerCompat.from(context)
        if (status == HelperStatus.RUNNING || status == HelperStatus.STARTING) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!context.notificationsAllowed()) return
        ensureChannel()
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(stat_sys_warning)
                .setContentTitle("FingertipsMCP helper is not running")
                .setContentText(text(status))
                .setContentIntent(open)
                .addAction(
                    NotificationCompat.Action.Builder(
                            stat_sys_warning,
                            "Start helper",
                            startHelper(),
                        )
                        .build()
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
        val notification = builder.build()
        // notificationsAllowed() already checked POST_NOTIFICATIONS. Lint does not see that call.
        @SuppressLint("MissingPermission") manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startHelper(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, HelperStartReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun text(status: HelperStatus): String =
        when (status) {
            HelperStatus.NEEDS_USB -> "Start it over USB. The command is in the FingertipsMCP app."
            HelperStatus.NEEDS_PAIRING ->
                "Open FingertipsMCP and tap Wireless debugging, or start it over USB."
            HelperStatus.RUNNING,
            HelperStatus.STARTING,
            HelperStatus.STOPPED -> "The command is in the FingertipsMCP app."
        }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Helper", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private companion object {
        const val CHANNEL = "helper"
        // The listener foreground service uses id 7. Sharing it replaces that notification, and
        // cancel() cannot remove a foreground notification, so the stale text stays up.
        const val NOTIFICATION_ID = 8
    }
}
