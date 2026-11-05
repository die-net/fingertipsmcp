package net.die.phoneapi.stream

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
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.helperclient.HelperConnection
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.DisplayInfo

data class VideoSpec(val maxSize: Int, val fps: Int, val bitRate: Int)

/**
 * One H.264 encoder shared by every video viewer. The helper mirrors the display into a
 * [FrameRelay], which draws onto the encoder's input surface and redraws the last frame on demand.
 * A joining client receives the parameter sets and a new keyframe, even on a still screen. A viewer
 * that falls behind drops frames until the next keyframe, and asks for one (see [FrameQueue]).
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
    private val subscribers = CopyOnWriteArrayList<Viewer>()
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
    private var listening = false
    private var displayJob: Job? = null

    /** Only the codec's callback thread uses this, through [emitFrame]. */
    private val keyRequests = KeyRequests()

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
        val viewer = Viewer()
        lease.opened()
        var attached = false
        try {
            val (header, config) =
                gate.withLock {
                    subscribers.add(viewer)
                    attached = true
                    // A failed encoder is gone while its closing viewers are still detaching.
                    if (codec == null) withContext(io) { startEncoder(spec) }
                    headerJson to configFrame
                }
            session.send(Frame.Text(header))
            config?.let { session.send(Frame.Binary(true, it)) }
            requestSync()
            sendUntilClientLeaves(session) { sendFrames(session, viewer) }
            viewer.failure?.let {
                session.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, it))
            }
        } finally {
            if (attached) withContext(NonCancellable) { detach(viewer) }
            viewer.close()
            lease.closed()
        }
    }

    private suspend fun detach(viewer: Viewer) {
        gate.withLock {
            subscribers.remove(viewer)
            if (subscribers.isEmpty()) withContext(io) { stopEncoder() }
        }
    }

    private suspend fun sendFrames(session: DefaultWebSocketServerSession, viewer: Viewer) {
        var open = true
        while (open) {
            // select is biased to the first clause, so a size-change header always goes out
            // before the codec-config frame of the encoder that follows it.
            val out: Frame? = select {
                viewer.headers.onReceiveCatching { it.getOrNull()?.let(Frame::Text) }
                viewer.queue.frames.onReceiveCatching { result ->
                    result.getOrNull()?.let { Frame.Binary(true, it) }
                }
            }
            if (out == null) open = false else session.send(out)
        }
    }

    private fun startEncoder(spec: VideoSpec) {
        val screen = display()
        val (width, height) = scaledSize(screen.widthPx, screen.heightPx, spec.maxSize)
        beginEncoder(spec, width, height)
    }

    private suspend fun watchDisplay() {
        while (listening) {
            withTimeoutOrNull(DISPLAY_POLL_MS) { displayChanges.receive() }
            reconfigureIfNeeded()
        }
    }

    private suspend fun reconfigureIfNeeded() {
        gate.withLock {
            val spec = activeSpec ?: return@withLock
            if (!running || subscribers.isEmpty()) return@withLock
            withContext(io) { reconfigure(spec) }
        }
    }

    private fun reconfigure(spec: VideoSpec) {
        val screen = display()
        val (width, height) = scaledSize(screen.widthPx, screen.heightPx, spec.maxSize)
        if (
            !shouldReconfigure(
                activeWidth,
                activeHeight,
                screen.widthPx,
                screen.heightPx,
                spec.maxSize,
            )
        ) {
            return
        }
        releaseEncoder()
        headerJson = videoHeader(width, height, spec)
        emitHeader(headerJson)
        try {
            beginEncoder(spec, width, height)
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

    private fun beginEncoder(spec: VideoSpec, width: Int, height: Int) {
        headerJson = videoHeader(width, height, spec)
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
            val frames = FrameRelay(surface, width, height)
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

    private suspend fun failLive(owner: MediaCodec) {
        gate.withLock {
            if (codec !== owner) return@withLock
            withContext(io) { stopEncoder() }
            closeViewers(ENCODER_STOPPED)
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
        emitFrame(packFrame(type, info.presentationTimeUs, payload))
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
        emitFrame(packed)
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

    private fun emitFrame(frame: ByteArray) {
        for (viewer in subscribers) viewer.queue.offer(frame)
        if (subscribers.none { it.queue.waitsForKey }) {
            keyRequests.satisfied()
        } else if (keyRequests.due(SystemClock.uptimeMillis())) {
            requestSync()
        }
    }

    private fun emitHeader(json: String) {
        for (viewer in subscribers) {
            // Queued frames are not shown at the new picture size.
            viewer.queue.drain()
            viewer.headers.trySend(json)
        }
    }

    private fun closeViewers(reason: String) {
        for (viewer in subscribers) {
            viewer.failure = reason
            viewer.close()
        }
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

    /**
     * Frames and a size-change header travel on separate channels so a full frame queue cannot drop
     * the header.
     */
    private class Viewer {
        val queue = FrameQueue(CHANNEL_CAP)
        val headers = Channel<String>(Channel.CONFLATED)

        /** Why the stream closed this viewer, sent as the close reason. */
        @Volatile var failure: String? = null

        fun close() {
            queue.frames.close()
            headers.close()
        }
    }

    private companion object {
        const val TAG = "PhoneApiStream"
        const val CHANNEL_CAP = 4

        /**
         * Key frames on a timer are only a backstop. Joining viewers and viewers that dropped a
         * frame ask for one, so a still screen does not send a full picture every second.
         */
        const val KEY_FRAME_INTERVAL_S = 10
        const val DISPLAY_POLL_MS = 400L
        const val ENCODER_STOPPED = "The video encoder stopped"
        const val HELPER_STOPPED = "The shell helper stopped"
    }
}
