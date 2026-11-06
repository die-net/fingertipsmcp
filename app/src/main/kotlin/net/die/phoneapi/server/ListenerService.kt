package net.die.phoneapi.server

import android.R.drawable.stat_sys_warning
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import net.die.phoneapi.PhoneApiApp
import net.die.phoneapi.R
import net.die.phoneapi.ui.MainActivity

/** Holds the abstract-socket listener for an adb session. */
class ListenerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        PhoneApiApp.graph.serverController.acquire(HOLDER)
        return START_STICKY
    }

    override fun onDestroy() {
        PhoneApiApp.graphOrNull()?.serverController?.release(HOLDER)
        super.onDestroy()
    }

    private fun notification() = run {
        // Creating it every time renames the channel on an existing install. The user's settings
        // for the channel stay as they are.
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Keeps PhoneAPI running",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(stat_sys_warning)
            .setContentTitle(getString(R.string.listener_title))
            .setContentText(getString(R.string.listener_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private companion object {
        const val CHANNEL = "phoneapi-listener"
        const val NOTIFICATION_ID = 7
        const val HOLDER = "service"
    }
}
