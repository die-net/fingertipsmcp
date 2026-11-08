package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpSession
import com.fingertipsmcp.browser.evaluateIn
import com.fingertipsmcp.model.Capabilities
import com.fingertipsmcp.model.DeviceInfo
import com.fingertipsmcp.model.DeviceStateSummary
import com.fingertipsmcp.model.DisplayInfo
import com.fingertipsmcp.model.HelperStatus
import com.fingertipsmcp.model.ImeState
import com.fingertipsmcp.model.KeyguardState
import com.fingertipsmcp.model.ScreenState
import com.fingertipsmcp.server.DeviceFacts
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

/** The screen as a page sees it, in CSS pixels, and its device pixel ratio. */
data class HostScreen(val width: Int, val height: Int, val pixelRatio: Float)

/**
 * `device_info` for the computer: the OS in manufacturer and release, the browser in model, and the
 * screen as Chrome reports it. helper is `running` while the browser is up. Browser input is the
 * mouse and keyboard over DevTools.
 */
class HostDevice(
    private val supervisor: BrowserSupervisor,
    private val version: String,
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val osVersion: String = System.getProperty("os.version").orEmpty(),
) : DeviceFacts {
    @Volatile var screen: HostScreen? = null

    override fun info(): DeviceInfo {
        val shown = screen
        val ratio = shown?.pixelRatio ?: 1f
        return DeviceInfo(
            manufacturer = osName,
            model = supervisor.current?.product ?: "Chrome (not running)",
            sdkInt = 0,
            release = osVersion,
            appVersion = version,
            display =
                DisplayInfo(
                    widthPx = ((shown?.width ?: 0) * ratio).roundToInt(),
                    heightPx = ((shown?.height ?: 0) * ratio).roundToInt(),
                    densityDpi = (CSS_DPI * ratio).roundToInt(),
                    rotation = 0,
                    refreshRate = REFRESH_HZ,
                ),
            state =
                DeviceStateSummary(
                    screen = ScreenState.ON,
                    keyguard = KeyguardState(locked = false, secure = false),
                    ime = ImeState(visible = false),
                ),
            helper = if (supervisor.current != null) HelperStatus.RUNNING else HelperStatus.STOPPED,
            uiAutomationConnected = false,
            capabilities = capabilities(),
        )
    }

    override fun capabilities(): Capabilities =
        Capabilities(
            uiSnapshot = false,
            inputInject = false,
            textKeyevent = false,
            screenshotHelper = true,
            uiStableIds = false,
            appsManage = false,
            logcatAll = false,
            browserCdp = true,
            // The viewer captures the tab Chrome shows, picture and sound.
            streamVideoMirror = true,
            streamAudioSubmix = true,
            encoderLowLatency = false,
            adbWireless = false,
            settingsSecure = false,
            clipboard = false,
        )

    override fun versionName(): String = version

    /** Reads the screen from a page, in an isolated world. */
    suspend fun measure(page: CdpSession) {
        val value =
            evaluateIn(
                    page,
                    "[screen.width, screen.height, devicePixelRatio]",
                    awaitPromise = false,
                )
                .value as? JsonArray ?: return
        val numbers = value.map { (it as? JsonPrimitive)?.floatOrNull ?: return }
        if (numbers.size != SCREEN_FIELDS) return
        screen = HostScreen(numbers[0].roundToInt(), numbers[1].roundToInt(), numbers[2])
    }

    private companion object {
        const val CSS_DPI = 96
        const val REFRESH_HZ = 60f
        const val SCREEN_FIELDS = 3
    }
}
