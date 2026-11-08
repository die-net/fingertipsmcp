package com.fingertipsmcp.input

import com.fingertipsmcp.helper.AxisRange
import com.fingertipsmcp.helper.TouchscreenInfo

/**
 * Where a finger sits on each touchscreen axis, as a fraction from min to max. [steady] is the
 * fixed contact used when humanize is off. A humanized stroke varies these from sample to sample.
 */
internal data class FingerShape(
    val pressure: Float,
    val size: Float,
    val major: Float,
    val minor: Float,
    val orientation: Float,
) {
    companion object {
        val steady =
            FingerShape(
                PRESSURE_FRACTION,
                SIZE_FRACTION,
                SIZE_FRACTION,
                SIZE_FRACTION,
                CENTER_FRACTION,
            )
    }
}

/**
 * The ellipse [InjectTouchBackend] stamps on an injected touch. CDP touches use the same numbers,
 * converted the way Chrome turns a MotionEvent into a touch: radius is half the major or minor axis
 * in CSS pixels, force is pressure, and rotation is orientation in degrees.
 */
internal data class FingerContact(
    val pressure: Float,
    val size: Float,
    val touchMajor: Float,
    val touchMinor: Float,
    val orientation: Float,
) {
    fun toCdp(dipScale: Float): CdpContact {
        val scale = dipScale.takeIf { it > 0f } ?: 1f
        return CdpContact(
            radiusX = touchMajor / 2f / scale,
            radiusY = touchMinor / 2f / scale,
            force = pressure,
            rotationAngle = Math.toDegrees(orientation.toDouble()).toFloat(),
        )
    }

    companion object {
        /**
         * Matches the helper's fallback touchscreen, whose axes run from 0 to 1 (orientation 0).
         */
        val fallback: FingerContact = from(fallbackTouchscreen())

        fun from(screen: TouchscreenInfo, shape: FingerShape = FingerShape.steady): FingerContact =
            FingerContact(
                pressure = along(screen.pressure, shape.pressure),
                size = along(screen.size, shape.size),
                touchMajor = along(screen.touchMajor, shape.major),
                touchMinor = along(screen.touchMinor, shape.minor),
                orientation = along(screen.orientation, shape.orientation),
            )
    }
}

/** Fields of one `Input.dispatchTouchEvent` touch point, in CSS pixels. */
internal data class CdpContact(
    val radiusX: Float,
    val radiusY: Float,
    val force: Float,
    val rotationAngle: Float,
)

private fun along(range: AxisRange?, fraction: Float): Float {
    if (range == null || range.max <= range.min) return range?.min ?: 0f
    return range.min + (range.max - range.min) * fraction
}

internal fun fallbackTouchscreen(): TouchscreenInfo =
    TouchscreenInfo().apply {
        pressure = axis(0f, 1f)
        size = axis(0f, 1f)
        touchMajor = axis(0f, 1f)
        touchMinor = axis(0f, 1f)
        orientation = axis(0f, 0f)
    }

private fun axis(min: Float, max: Float): AxisRange =
    AxisRange().apply {
        this.min = min
        this.max = max
    }

/**
 * A point [fraction] of the way from min to max, so a finger is not the exact center of the range.
 */
private const val PRESSURE_FRACTION = 0.55f
private const val SIZE_FRACTION = 0.08f
private const val CENTER_FRACTION = 0.5f
