package com.fingertipsmcp.stream

/** A binary frame's first byte for codec config: H.264 parameter sets or an Opus header. */
const val FRAME_CONFIG = 1

/** A binary frame's first byte for a key frame, which decodes on its own. */
const val FRAME_KEY = 2

/** A binary frame's first byte for a delta frame, which decodes against the frames before it. */
const val FRAME_DELTA = 3

private const val HEADER_BYTES = 9

/**
 * One binary websocket frame: type byte, big-endian presentation timestamp in microseconds, then
 * the payload. Video payloads are Annex-B. Audio payloads are raw Opus or AAC access units.
 *
 * Each socket also sends text frames of a `VideoHeader` or an `AudioHeader`. Video sends that JSON
 * again when the encoded size changes; the binary layout above stays the same. The desktop viewer's
 * extension writes the same layout in `offscreen.js`.
 */
fun packFrame(type: Int, ptsUs: Long, payload: ByteArray): ByteArray {
    val out = ByteArray(HEADER_BYTES + payload.size)
    out[0] = type.toByte()
    var shift = 56
    for (index in 1 until HEADER_BYTES) {
        out[index] = ((ptsUs ushr shift) and 0xff).toByte()
        shift -= 8
    }
    payload.copyInto(out, HEADER_BYTES)
    return out
}
