package com.fingertipsmcp.stream

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.fingertipsmcp.helperclient.HelperConnection
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.DisplayInfo
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One H.264 encoder shared by every video viewer. The helper mirrors the display into a
 * [FrameRelay], which draws onto the encoder's input surface and redraws the last frame on demand.
 * A joining client receives the parameter sets and a new keyframe, even on a still screen. A viewer
 * that falls behind drops frames until the next keyframe, and asks for one (see [VideoViewers]).
 *
 * If the helper dies, the mirror dies with it, so every viewer is closed and can reconnect.
 *
 * When the display size changes, the encoder and mirror are rebuilt and each viewer receives
 * another [videoHeader] text frame, then a codec-config frame and a keyframe. Binary frames stay
 * [packFrame] encoded.
 */
internal class VideoStream(
    private val lease: StreamLease,
    private val helper: HelperConnection,
    private val display: () -> DisplayInfo,
    private val context: Context,
    private val io: CoroutineDispatcher,
) {
    private val gate = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val viewers = VideoViewers(::requestSync, SystemClock::uptimeMillis)
    private val displayChanges = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var codec: MediaCodec? = null

    @Volatile private var running = false

    @Volatile private var configFrame: ByteArray? = null

    @Volatile private var relay: FrameRelay? = null

    private var headerJson: String = ""
    private var spsPps: ByteArray? = null
    private var activeSpec: VideoSpec? = null
    private var activeWidth = 0
    private var activeHeight = 0

    /** The screen size [headerJson] names, which the viewer maps its points with. */
    private var namedWidth = 0
    private var namedHeight = 0
    private var listening = false
    private var displayJob: Job? = null

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                displayChanges.trySend(Unit)
            }
        }

    init {
        helper.addDeathListener { scope.launch { failPipeline(HELPER_STOPPED) } }
    }

    suspend fun serve(session: DefaultWebSocketServerSession, spec: VideoSpec) {
        val viewer = VideoViewer()
        lease.opened()
        var attached = false
        try {
            val (header, config) =
                gate.withLock {
                    viewers.add(viewer)
                    attached = true
                    // A failed encoder is gone while its closing viewers are still detaching.
                    if (codec == null) withContext(io) { startEncoder(spec) }
                    headerJson to configFrame
                }
            session.send(Frame.Text(header))
            config?.let { session.send(Frame.Binary(true, it)) }
            requestSync()
            viewer.send(session)
        } finally {
            if (attached) withContext(NonCancellable) { detach(viewer) }
            viewer.close()
            lease.closed()
        }
    }

    private suspend fun detach(viewer: VideoViewer) {
        gate.withLock {
            viewers.remove(viewer)
            if (viewers.isEmpty()) withContext(io) { stopEncoder() }
        }
    }

    private fun startEncoder(spec: VideoSpec) {
        val screen = display()
        val (width, height) = scaledSize(screen.widthPx, screen.heightPx, spec.maxSize)
        beginEncoder(spec, width, height, screen)
    }

    /**
     * Rebuilds for each change the display listener reports. As read in AOSP, not measured,
     * DisplayManagerGlobal drops its cached display info before it calls listeners, so [display]
     * already reads the new size.
     */
    private suspend fun watchDisplay() {
        while (true) {
            displayChanges.receive()
            reconfigureIfNeeded()
        }
    }

    private suspend fun reconfigureIfNeeded() {
        gate.withLock {
            val spec = activeSpec ?: return@withLock
            if (!running || viewers.isEmpty()) return@withLock
            withContext(io) { reconfigure(spec) }
        }
    }

    private fun reconfigure(spec: VideoSpec) {
        val screen = display()
        val encoded = activeWidth to activeHeight
        when (displayChange(encoded, namedWidth to namedHeight, screen, spec.maxSize)) {
            DisplayChange.NONE -> return
            DisplayChange.HEADER -> {
                // The picture keeps its size, so frames already queued still show.
                nameScreen(videoHeader(activeWidth, activeHeight, spec, screen), screen)
                viewers.sendHeader(headerJson)
                return
            }
            DisplayChange.ENCODER -> Unit
        }
        val (width, height) = scaledSize(screen.widthPx, screen.heightPx, spec.maxSize)
        releaseEncoder()
        nameScreen(videoHeader(width, height, spec, screen), screen)
        // Queued frames are not shown at the new picture size.
        viewers.emitHeader(headerJson)
        try {
            beginEncoder(spec, width, height, screen)
        } catch (e: CancellationException) {
            stopEncoder()
            closeViewers(ENCODER_STOPPED)
            throw e
        } catch (e: ApiException) {
            Log.w(TAG, "reconfigure", e)
            stopEncoder()
            closeViewers(ENCODER_STOPPED)
            return
        }
        requestSync()
    }

    /** Keeps [header] for viewers that join, and the screen size it names. */
    private fun nameScreen(header: String, screen: DisplayInfo) {
        headerJson = header
        namedWidth = screen.widthPx
        namedHeight = screen.heightPx
    }

    private fun beginEncoder(spec: VideoSpec, width: Int, height: Int, screen: DisplayInfo) {
        nameScreen(videoHeader(width, height, spec, screen), screen)
        val encoder =
            try {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } catch (e: IOException) {
                throw streamError(e)
            }
        try {
            encoder.setCallback(callback(encoder))
            configure(encoder, videoFormat(encoder, width, height, spec))
            val surface = encoder.createInputSurface()
            encoder.start()
            running = true
            codec = encoder
            // A relay that cannot draw would leave viewers a frozen picture that looks alive, so
            // its failure ends the stream as the encoder's own does, and viewers reconnect.
            val frames =
                FrameRelay(surface, width, height) { reason ->
                    scope.launch { failLive(encoder, reason) }
                }
            relay = frames
            val started =
                helper.require().startMirror(frames.input, width, height, Display.DEFAULT_DISPLAY)
            if (!started) {
                throw ApiException.unavailable(
                    "stream_error",
                    "The helper could not start display mirroring. The shell helper has to be running.",
                )
            }
            activeSpec = spec
            activeWidth = width
            activeHeight = height
            ensureListening()
            Log.i(TAG, "encoder ${width}x$height")
        } catch (e: CancellationException) {
            failStart(encoder)
            throw e
        } catch (e: RemoteException) {
            failStart(encoder)
            throw streamError(e)
        } catch (e: IllegalStateException) {
            failStart(encoder)
            throw streamError(e)
        } catch (e: ApiException) {
            failStart(encoder)
            throw e
        }
    }

    private fun configure(encoder: MediaCodec, format: MediaFormat) {
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: IllegalArgumentException) {
            throw streamError(e)
        } catch (e: MediaCodec.CodecException) {
            throw streamError(e)
        }
    }

    private fun videoFormat(
        encoder: MediaCodec,
        width: Int,
        height: Int,
        spec: VideoSpec,
    ): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_S)
            applyLowLatency(encoder, this)
        }

    private fun applyLowLatency(encoder: MediaCodec, format: MediaFormat) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val supported =
            try {
                encoder.codecInfo
                    .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            } catch (_: IllegalArgumentException) {
                false
            }
        if (supported) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
    }

    private fun callback(owner: MediaCodec): MediaCodec.Callback =
        object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                if (this@VideoStream.codec !== owner) {
                    releaseQuietly(owner, index)
                    return
                }
                handleOutput(owner, index, info)
            }

            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                if (this@VideoStream.codec !== owner) return
                Log.e(TAG, "encoder", error)
                scope.launch { failLive(owner) }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                if (this@VideoStream.codec !== owner) return
                val csd0 = format.getByteBuffer("csd-0")?.let(::copyBuffer) ?: return
                val csd1 = format.getByteBuffer("csd-1")?.let(::copyBuffer)
                publishParameterSets(csd0, csd1)
            }
        }

    /**
     * Ends the stream [owner] encodes, if it is still the live one, closing viewers with [reason].
     */
    private suspend fun failLive(owner: MediaCodec, reason: String = ENCODER_STOPPED) {
        gate.withLock {
            if (codec !== owner) return@withLock
            withContext(io) { stopEncoder() }
            closeViewers(reason)
        }
    }

    private suspend fun failPipeline(reason: String) {
        gate.withLock {
            if (codec == null) return@withLock
            Log.w(TAG, reason)
            withContext(io) { stopEncoder() }
            closeViewers(reason)
        }
    }

    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val data =
            try {
                readOutput(codec, index, info)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "encoder output", e)
                return
            } ?: return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            publishParameterSets(data, null)
            return
        }
        val annex = accessUnitToAnnexB(data)
        if (annex.isEmpty()) return
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val payload = if (key) withParameterSets(annex) else annex
        val type = if (key) FRAME_KEY else FRAME_DELTA
        viewers.emitFrame(packFrame(type, info.presentationTimeUs, payload))
    }

    private fun readOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo): ByteArray? {
        if (!running || this.codec !== codec) {
            releaseQuietly(codec, index)
            return null
        }
        val output = codec.getOutputBuffer(index)
        val bytes =
            if (output != null && info.size > 0) {
                output.position(info.offset)
                output.limit(info.offset + info.size)
                ByteArray(info.size).also { output.get(it) }
            } else {
                null
            }
        codec.releaseOutputBuffer(index, false)
        return bytes
    }

    private fun publishParameterSets(csd0: ByteArray, csd1: ByteArray?) {
        if (!running) return
        val annex =
            try {
                codecConfigAnnexB(csd0, csd1)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "codec config", e)
                return
            }
        if (annex.isEmpty()) return
        spsPps = annex
        val packed = packFrame(FRAME_CONFIG, 0, annex)
        configFrame = packed
        viewers.emitFrame(packed)
    }

    private fun withParameterSets(annex: ByteArray): ByteArray {
        val sets = spsPps ?: return annex
        if (startsWithSps(annex)) return annex
        return sets + annex
    }

    private fun requestSync() {
        val current = codec ?: return
        if (!running) return
        val bundle = Bundle()
        bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        try {
            current.setParameters(bundle)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "sync frame", e)
        }
        // The request waits for the next input frame, which a still screen never sends.
        relay?.redraw()
    }

    private fun closeViewers(reason: String) {
        viewers.close(reason)
    }

    private fun ensureListening() {
        if (listening) return
        val manager = context.getSystemService(DisplayManager::class.java) ?: return
        listening = true
        displayJob = scope.launch { watchDisplay() }
        manager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        context
            .getSystemService(DisplayManager::class.java)
            ?.unregisterDisplayListener(displayListener)
        displayJob?.cancel()
        displayJob = null
    }

    private fun failStart(encoder: MediaCodec) {
        running = false
        if (codec === encoder) codec = null
        configFrame = null
        spsPps = null
        stopMirrorQuietly()
        releaseRelay()
        releaseCodec(encoder)
    }

    private fun releaseEncoder() {
        running = false
        val current = codec
        codec = null
        configFrame = null
        spsPps = null
        stopMirrorQuietly()
        releaseRelay()
        if (current != null) releaseCodec(current)
    }

    /** The relay draws onto the codec's surface, so it has to stop before the codec does. */
    private fun releaseRelay() {
        relay?.release()
        relay = null
    }

    private fun stopEncoder() {
        releaseEncoder()
        activeSpec = null
        stopListening()
    }

    private fun stopMirrorQuietly() {
        try {
            helper.getOrNull()?.stopMirror()
        } catch (e: RemoteException) {
            Log.w(TAG, "stopMirror", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopMirror", e)
        }
    }

    private fun releaseCodec(current: MediaCodec) {
        try {
            current.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "encoder stop", e)
        }
        current.release()
    }

    private fun releaseQuietly(codec: MediaCodec, index: Int) {
        try {
            codec.releaseOutputBuffer(index, false)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "encoder release", e)
        }
    }

    private fun copyBuffer(buffer: ByteBuffer): ByteArray {
        val view = buffer.duplicate()
        val out = ByteArray(view.remaining())
        view.get(out)
        return out
    }

    private fun streamError(error: Throwable): ApiException =
        ApiException(
            502,
            "stream_error",
            error.message?.takeIf { it.isNotBlank() }
                ?: "The shell helper stopped during display mirroring",
            cause = error,
        )

    private companion object {
        const val TAG = "FingertipsStream"

        /**
         * Key frames on a timer are only a backstop. Joining viewers and viewers that dropped a
         * frame ask for one, so a still screen does not send a full picture every second.
         */
        const val KEY_FRAME_INTERVAL_S = 10
        const val ENCODER_STOPPED = "The video encoder stopped"
        const val HELPER_STOPPED = "The shell helper stopped"
    }
}
