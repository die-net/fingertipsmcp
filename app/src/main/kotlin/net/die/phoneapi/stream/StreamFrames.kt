package net.die.phoneapi.stream

import kotlin.math.max
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.AudioHeader
import net.die.phoneapi.model.DisplayInfo
import net.die.phoneapi.model.VideoHeader

internal const val FRAME_CONFIG = 1
internal const val FRAME_KEY = 2
internal const val FRAME_DELTA = 3

private const val HEADER_BYTES = 9
private const val START_CODE_3 = 3
private const val START_CODE_4 = 4
private const val NAL_SPS = 7
private const val NAL_LENGTH_BYTES = 4
private const val AVCC_HEADER_BYTES = 5

/** Longest side of an encoded frame, in pixels. */
internal const val MIN_MAX_SIZE = 160
internal const val MAX_MAX_SIZE = 1920
internal const val DEFAULT_MAX_SIZE = 1280
internal const val MIN_FPS = 1
internal const val MAX_FPS = 60
internal const val DEFAULT_FPS = 30
internal const val MIN_BIT_RATE = 100_000
internal const val MAX_BIT_RATE = 20_000_000
internal const val DEFAULT_BIT_RATE = 4_000_000

/**
 * Text frame sent on the video socket before binary frames, and again when the encoded size or the
 * screen's size changes. [screen] is the display the frames show, so the viewer maps its points
 * without asking for it separately.
 */
internal fun videoHeader(width: Int, height: Int, spec: VideoSpec, screen: DisplayInfo): String =
    ApiJson.encodeToString(
        VideoHeader.serializer(),
        VideoHeader("avc", width, height, spec.fps, spec.bitRate, screen.widthPx, screen.heightPx),
    )

private val AOPUS_MAGIC = "AOPUSHDR".encodeToByteArray()
private const val AOPUS_HEADER = 16

/**
 * Android's Opus encoder wraps the identification header as `AOPUSHDR` plus a length. WebCodecs
 * wants the `OpusHead` bytes on their own. Other codec configs, including AAC, are unchanged.
 */
internal fun audioCodecDescription(data: ByteArray): ByteArray {
    if (
        data.size < AOPUS_HEADER ||
            !data.copyOfRange(0, AOPUS_MAGIC.size).contentEquals(AOPUS_MAGIC)
    ) {
        return data
    }
    val size = (data[8].toInt() and 0xff) or ((data[9].toInt() and 0xff) shl 8)
    val end = AOPUS_HEADER + size
    if (size <= 0 || end > data.size) return data
    return data.copyOfRange(AOPUS_HEADER, end)
}

/** Text frame sent once on the audio socket before binary frames. */
internal fun audioHeader(codec: String, sampleRate: Int, channels: Int): String =
    ApiJson.encodeToString(AudioHeader.serializer(), AudioHeader(codec, sampleRate, channels))

/**
 * One binary websocket frame: type byte, big-endian presentation timestamp in microseconds, then
 * the payload. Video payloads are Annex-B. Audio payloads are raw Opus or AAC access units.
 *
 * Each socket also sends text frames of [videoHeader] or [audioHeader]. Video sends that JSON again
 * when the encoded size changes; the binary layout above stays the same.
 */
internal fun packFrame(type: Int, ptsUs: Long, payload: ByteArray): ByteArray {
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

/** Even encoder size that fits [maxSize] on the long side, keeping the aspect ratio. */
internal fun scaledSize(width: Int, height: Int, maxSize: Int): Pair<Int, Int> {
    val longSide = max(width, height).coerceAtLeast(1)
    val scaledWidth: Int
    val scaledHeight: Int
    if (longSide <= maxSize) {
        scaledWidth = width
        scaledHeight = height
    } else {
        scaledWidth = (width.toLong() * maxSize / longSide).toInt()
        scaledHeight = (height.toLong() * maxSize / longSide).toInt()
    }
    return even(scaledWidth) to even(scaledHeight)
}

private fun even(value: Int): Int = (value and 1.inv()).coerceAtLeast(2)

/** What a display change asks of a running video stream. */
internal enum class DisplayChange {
    NONE,
    /** The screen's size changed but encodes at the same size, so only the header goes again. */
    HEADER,
    /** The encoded size changed, so the encoder and mirror are rebuilt, with a new header. */
    ENCODER,
}

/**
 * What the display, now [shown], needs from a stream that encodes at [encoded] for the screen size
 * its last header named, [named]. Both are width to height in pixels.
 */
internal fun displayChange(
    encoded: Pair<Int, Int>,
    named: Pair<Int, Int>,
    shown: DisplayInfo,
    maxSize: Int,
): DisplayChange =
    when {
        scaledSize(shown.widthPx, shown.heightPx, maxSize) != encoded -> DisplayChange.ENCODER
        (shown.widthPx to shown.heightPx) != named -> DisplayChange.HEADER
        else -> DisplayChange.NONE
    }

internal fun isAnnexB(data: ByteArray): Boolean {
    if (data.size >= START_CODE_4 && data[0] == 0.toByte() && data[1] == 0.toByte()) {
        if (data[2] == 1.toByte()) return true
        if (data[2] == 0.toByte() && data[3] == 1.toByte()) return true
    }
    return false
}

/** AVCDecoderConfigurationRecord (`csd-0`) to Annex-B SPS and PPS. */
internal fun avcDecoderConfigToAnnexB(avcc: ByteArray): ByteArray {
    require(avcc.size >= AVCC_HEADER_BYTES + 2 && avcc[0] == 1.toByte()) {
        "not an AVC decoder config"
    }
    val nals = ArrayList<ByteArray>()
    var index = AVCC_HEADER_BYTES
    val spsCount = avcc[index].toInt() and 0x1f
    index += 1
    repeat(spsCount) { index = readLengthPrefixed(avcc, index, nals) }
    require(index < avcc.size) { "AVC decoder config has no PPS" }
    val ppsCount = avcc[index].toInt() and 0xff
    index += 1
    repeat(ppsCount) { index = readLengthPrefixed(avcc, index, nals) }
    return nalsToAnnexB(nals)
}

/** Length-prefixed NALs (4-byte big-endian lengths) to Annex-B. Already-Annex-B input is kept. */
internal fun accessUnitToAnnexB(data: ByteArray): ByteArray {
    if (isAnnexB(data)) return data
    val nals = ArrayList<ByteArray>()
    var index = 0
    while (index + NAL_LENGTH_BYTES <= data.size) {
        val length = readInt(data, index)
        val start = index + NAL_LENGTH_BYTES
        if (length <= 0 || start + length > data.size) break
        nals += data.copyOfRange(start, start + length)
        index = start + length
    }
    return nalsToAnnexB(nals)
}

/** Codec config from MediaCodec `csd-0` / `csd-1`, whichever shape the encoder produced. */
internal fun codecConfigAnnexB(csd0: ByteArray, csd1: ByteArray?): ByteArray {
    val first = parameterSet(csd0)
    if (csd1 == null) return first
    return first + parameterSet(csd1)
}

private fun parameterSet(data: ByteArray): ByteArray =
    when {
        isAnnexB(data) -> data
        data.isNotEmpty() && data[0] == 1.toByte() -> avcDecoderConfigToAnnexB(data)
        else -> nalsToAnnexB(listOf(data))
    }

internal fun startsWithSps(annexB: ByteArray): Boolean {
    val header = startCodeLength(annexB) ?: return false
    if (header >= annexB.size) return false
    return (annexB[header].toInt() and 0x1f) == NAL_SPS
}

private fun readLengthPrefixed(data: ByteArray, offset: Int, into: MutableList<ByteArray>): Int {
    require(offset + 2 <= data.size) { "truncated AVC decoder config" }
    val length = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    val start = offset + 2
    require(length > 0 && start + length <= data.size) { "truncated AVC decoder config" }
    into += data.copyOfRange(start, start + length)
    return start + length
}

private fun readInt(data: ByteArray, offset: Int): Int {
    return ((data[offset].toInt() and 0xff) shl 24) or
        ((data[offset + 1].toInt() and 0xff) shl 16) or
        ((data[offset + 2].toInt() and 0xff) shl 8) or
        (data[offset + 3].toInt() and 0xff)
}

private fun nalsToAnnexB(nals: List<ByteArray>): ByteArray {
    var size = 0
    for (nal in nals) size += START_CODE_4 + nal.size
    val out = ByteArray(size)
    var offset = 0
    for (nal in nals) {
        out[offset + 3] = 1
        nal.copyInto(out, offset + START_CODE_4)
        offset += START_CODE_4 + nal.size
    }
    return out
}

private fun startCodeLength(data: ByteArray): Int? {
    if (data.size >= START_CODE_4 && data[0] == 0.toByte() && data[1] == 0.toByte()) {
        if (data[2] == 0.toByte() && data[3] == 1.toByte()) return START_CODE_4
        if (data[2] == 1.toByte()) return START_CODE_3
    }
    return null
}
