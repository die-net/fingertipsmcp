package com.fingertipsmcp.helperclient

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/** True when Do Not Disturb is on, which can hide a notification from the shade too. */
internal fun Context.doNotDisturbOn(): Boolean =
    isDoNotDisturb(getSystemService(NotificationManager::class.java).currentInterruptionFilter)

/** True for every filter that limits notifications. An unknown filter limits nothing we know of. */
internal fun isDoNotDisturb(filter: Int): Boolean =
    filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
        filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN

internal fun Context.notificationsAllowed(): Boolean {
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}
