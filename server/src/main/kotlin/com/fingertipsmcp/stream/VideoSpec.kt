package com.fingertipsmcp.stream

/** What a video viewer asked for. Bounds are checked by the stream route. */
data class VideoSpec(val maxSize: Int, val fps: Int, val bitRate: Int)

/** Longest side of an encoded frame, in pixels. */
const val MIN_MAX_SIZE = 160
const val MAX_MAX_SIZE = 1920
const val DEFAULT_MAX_SIZE = 1280
const val MIN_FPS = 1
const val MAX_FPS = 60
const val DEFAULT_FPS = 30
const val MIN_BIT_RATE = 100_000
const val MAX_BIT_RATE = 20_000_000
const val DEFAULT_BIT_RATE = 4_000_000
