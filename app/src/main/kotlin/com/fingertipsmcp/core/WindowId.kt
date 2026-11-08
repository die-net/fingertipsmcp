package com.fingertipsmcp.core

import com.fingertipsmcp.model.ApiException

/** `3` and `w3` are the same window id. */
fun windowId(value: String): Int =
    value.removePrefix("w").toIntOrNull()
        ?: throw ApiException.badRequest("window must be a window id like 3 or w3")
