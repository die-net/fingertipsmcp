package com.fingertipsmcp.helperclient

import com.fingertipsmcp.model.LogcatLine

internal object ThreadtimeLog {
    private val LINE =
        Regex(
            """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.+?):\s(.*)$"""
        )

    fun parse(line: String): LogcatLine? {
        val match = LINE.matchEntire(line) ?: return null
        val time = match.groupValues[1]
        val pid = match.groupValues[2]
        val tid = match.groupValues[3]
        val level = match.groupValues[4]
        val tag = match.groupValues[5].trim()
        if (tag.isEmpty()) return null
        return LogcatLine(
            time = time,
            pid = pid.toLong(),
            tid = tid.toLong(),
            level = level,
            tag = tag,
            message = match.groupValues[6],
        )
    }
}
