package net.die.phoneapi.helperclient

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import net.die.phoneapi.model.ApiException

/** Reads and, when permitted, toggles wireless debugging. */
internal class SecureSettings(private val resolver: ContentResolver) {
    fun wirelessEnabled(): Boolean = Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1

    fun setWirelessEnabled(on: Boolean) {
        Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, if (on) 1 else 0)
    }

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }
}

/**
 * True when the app should ask the helper for `WRITE_SECURE_SETTINGS`. Android 10 has no use for
 * it.
 */
internal fun shouldGrantSecureSettings(sdk: Int, granted: Boolean): Boolean =
    sdk >= Build.VERSION_CODES.R && !granted

/**
 * Grants `WRITE_SECURE_SETTINGS` through the helper, which runs as the shell user and so can, to
 * save anyone running `pm grant` by hand. A manifest entry alone can't grant it.
 */
internal suspend fun grantSecureSettings(context: Context, shell: HelperShell) {
    val granted =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    if (!shouldGrantSecureSettings(Build.VERSION.SDK_INT, granted)) return
    try {
        val result =
            shell.exec(
                listOf(
                    "pm",
                    "grant",
                    context.packageName,
                    Manifest.permission.WRITE_SECURE_SETTINGS,
                )
            )
        if (result.exit != 0) Log.w(TAG, "pm grant exited ${result.exit}")
    } catch (e: ApiException) {
        Log.w(TAG, "Could not grant secure settings", e)
    }
}

private const val TAG = "PhoneApiHelper"
