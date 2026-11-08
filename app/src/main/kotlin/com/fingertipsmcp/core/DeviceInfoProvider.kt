package com.fingertipsmcp.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import com.fingertipsmcp.model.Capabilities
import com.fingertipsmcp.model.DeviceInfo
import com.fingertipsmcp.model.DisplayInfo
import com.fingertipsmcp.model.HelperStatus

/** Builds [DeviceInfo], including the runtime capability map. */
class DeviceInfoProvider(
    private val context: Context,
    private val state: DeviceStateTracker,
    private val isHelperRunning: () -> Boolean,
    private val isUiAutomationConnected: () -> Boolean,
    private val helperStatus: () -> HelperStatus,
    private val helperRecoveredAtMs: () -> Long?,
    private val appFreezer: () -> Boolean? = { null },
    private val chromeKept: () -> Boolean = { false },
) {
    private val cachedVersion: String by lazy { readVersion() }

    fun info(): DeviceInfo {
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE,
            appVersion = cachedVersion,
            display = display(),
            state = state.refresh(),
            helper = helperStatus(),
            helperRecoveredAtMs = helperRecoveredAtMs(),
            uiAutomationConnected = isUiAutomationConnected(),
            appFreezer = appFreezer(),
            chromeKept = chromeKept(),
            capabilities = capabilities(),
        )
    }

    /** Package version only, so a tool listing does not also query the display. */
    fun versionName(): String = cachedVersion

    fun capabilities(): Capabilities {
        val sdk = Build.VERSION.SDK_INT
        val helper = isHelperRunning()
        val secureSettings =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        return Capabilities(
            uiSnapshot = helper,
            inputInject = helper,
            textKeyevent = helper,
            screenshotHelper = helper,
            uiStableIds = sdk >= Build.VERSION_CODES.TIRAMISU,
            appsManage = helper,
            logcatAll = helper,
            browserCdp = helper,
            streamVideoMirror = helper,
            streamAudioSubmix = helper && sdk >= Build.VERSION_CODES.R,
            encoderLowLatency = sdk >= Build.VERSION_CODES.R,
            adbWireless = sdk >= Build.VERSION_CODES.R,
            settingsSecure = secureSettings,
            clipboard = helper,
        )
    }

    fun display(): DisplayInfo {
        val display =
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = context.resources.displayMetrics
        val mode = display.mode
        val rotated = display.rotation % 2 == 1
        val (w, h) = mode.physicalWidth to mode.physicalHeight
        return DisplayInfo(
            widthPx = if (rotated) h else w,
            heightPx = if (rotated) w else h,
            densityDpi = metrics.densityDpi,
            rotation = display.rotation,
            refreshRate = mode.refreshRate,
            autoRotate =
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    0,
                ) == 1,
        )
    }

    private fun readVersion(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
}
