package com.fingertipsmcp.core

import com.fingertipsmcp.model.ApiException

internal const val DEFAULT_LOG_LEVEL = "I"

internal val LOG_LEVELS = setOf("V", "D", "I", "W", "E", "F")

/** Logcat tag, shared with MCP `logcat_tail` and `WS /v1/events`. */
internal val LOG_TAG = Regex("[A-Za-z0-9._-]{1,80}")

internal fun checkLogTag(tag: String, field: String) {
    if (!LOG_TAG.matches(tag)) {
        throw ApiException.badRequest(
            "$field must be letters, digits, dots, underscores, or hyphens"
        )
    }
}

/** Tag and minimum level for one logcat stream. A tag also silences every other tag. */
data class LogcatFilter(val tag: String?, val level: String)

/** `logcat -v threadtime -T 1` plus the priority filterspec, starting from the newest line. */
internal fun LogcatFilter.argv(): Array<String> =
    if (tag == null) {
        arrayOf("-v", "threadtime", "-T", "1", "*:$level")
    } else {
        arrayOf("-v", "threadtime", "-T", "1", "$tag:$level", "*:S")
    }
