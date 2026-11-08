package com.fingertipsmcp.power

import com.fingertipsmcp.model.ScreenOrientation

/**
 * System settings for one orientation request. [userRotation] is omitted when auto-rotate stays on.
 */
internal data class OrientationSetting(
    val accelerometer: Int,
    val userRotation: Int?,
    val message: String,
)

internal fun orientationSetting(
    orientation: ScreenOrientation,
    currentRotation: Int,
): OrientationSetting =
    when (orientation) {
        ScreenOrientation.AUTO -> OrientationSetting(1, null, "Auto-rotate is on")
        ScreenOrientation.LOCK -> locked(currentRotation)
        ScreenOrientation.R0 -> locked(0)
        ScreenOrientation.R90 -> locked(1)
        ScreenOrientation.R180 -> locked(2)
        ScreenOrientation.R270 -> locked(3)
    }

private fun locked(rotation: Int): OrientationSetting {
    val userRotation = rotation.coerceIn(0, 3)
    return OrientationSetting(0, userRotation, "Locked at ${userRotation * 90} degrees")
}
