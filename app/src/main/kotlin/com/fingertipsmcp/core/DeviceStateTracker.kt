package com.fingertipsmcp.core

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import com.fingertipsmcp.model.DeviceStateSummary
import com.fingertipsmcp.model.EventTypes
import com.fingertipsmcp.model.ImeData
import com.fingertipsmcp.model.ImeState
import com.fingertipsmcp.model.KeyguardState
import com.fingertipsmcp.model.ScreenState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks screen, keyguard, IME and foreground-package state. Screen and keyguard come from system
 * services and broadcasts. IME and foreground package are pushed by the helper's UiAutomation
 * session.
 */
class DeviceStateTracker(private val context: Context, private val bus: EventBus) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)

    private val imeState = MutableStateFlow(ImeState(visible = false))
    private val foreground = MutableStateFlow<String?>(null)
    private val summary = MutableStateFlow(compute())

    val state: StateFlow<DeviceStateSummary> = summary.asStateFlow()

    /**
     * The last published summary. Screen and keyguard are not broadcast for every change, so a
     * caller that needs them live uses [refresh].
     */
    val current: DeviceStateSummary
        get() = summary.value

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val type =
                    when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> EventTypes.SCREEN_ON
                        Intent.ACTION_SCREEN_OFF -> EventTypes.SCREEN_OFF
                        Intent.ACTION_USER_PRESENT -> EventTypes.USER_PRESENT
                        else -> return
                    }
                refresh()
                bus.emit(type)
            }
        }

    fun start() {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        // System broadcasts; no export flag is required for protected actions.
        context.registerReceiver(receiver, filter)
    }

    /** Re-reads screen and keyguard, publishes the result on [state], and returns it. */
    fun refresh(): DeviceStateSummary = compute().also { summary.value = it }

    fun setIme(next: ImeState) {
        val prev = imeState.value
        imeState.value = next
        refresh()
        if (prev.visible != next.visible) {
            bus.emit(
                if (next.visible) EventTypes.IME_SHOWN else EventTypes.IME_HIDDEN,
                ImeData(
                    packageName = next.packageName,
                    left = next.bounds?.left,
                    top = next.bounds?.top,
                    right = next.bounds?.right,
                    bottom = next.bounds?.bottom,
                ),
            )
        }
    }

    fun setForegroundPackage(pkg: String?) {
        if (foreground.value != pkg) {
            foreground.value = pkg
            refresh()
        }
    }

    val ime: ImeState
        get() = imeState.value

    private fun compute(): DeviceStateSummary {
        val displayState = displays.getDisplay(Display.DEFAULT_DISPLAY)?.state
        val screen =
            when {
                displayState == Display.STATE_DOZE || displayState == Display.STATE_DOZE_SUSPEND ->
                    ScreenState.DOZE
                power.isInteractive -> ScreenState.ON
                else -> ScreenState.OFF
            }
        return DeviceStateSummary(
            screen = screen,
            keyguard =
                KeyguardState(locked = keyguard.isKeyguardLocked, secure = keyguard.isDeviceSecure),
            ime = imeState.value,
            foregroundPackage = foreground.value,
        )
    }
}
