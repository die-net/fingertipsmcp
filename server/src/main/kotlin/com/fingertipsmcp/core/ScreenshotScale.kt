package com.fingertipsmcp.core

/**
 * Screenshot scale when the caller omits it. REST and MCP differ on purpose: a model call defaults
 * to a smaller image than `GET /v1/screenshot`.
 */
object ScreenshotScale {
    const val REST = 1f
    const val MCP = 0.5f
}
