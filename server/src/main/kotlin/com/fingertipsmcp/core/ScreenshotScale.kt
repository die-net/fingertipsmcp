package com.fingertipsmcp.core

import com.fingertipsmcp.model.ApiException

/**
 * Screenshot scale: its bounds, which the phone and FingertipsMCP for Chrome share, and its default
 * when the caller omits it. REST and MCP defaults differ on purpose: a model call defaults to a
 * smaller image than `GET /v1/screenshot`.
 */
object ScreenshotScale {
    const val REST = 1f
    const val MCP = 0.5f
    const val MIN = 0.1f
    const val MAX = 1f

    /** Refuses a scale outside [MIN]..[MAX] with 400 `bad_request`. */
    fun require(scale: Float) {
        if (scale !in MIN..MAX) throw ApiException.badRequest("scale must be $MIN to $MAX")
    }
}
