package net.die.phoneapi.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.helperclient.HelperConnection
import net.die.phoneapi.model.ApiException

/**
 * One audio encoder shared by every audio viewer. The helper writes PCM from remote submix, and
 * this encodes Opus when the device has that encoder, otherwise AAC. [OpusBundler] joins Opus
 * packets into 60 ms ones, and [SilenceGate] leaves out silent stretches.
 */
internal class AudioStream(
    private val lease: StreamLease,
    private val helper: HelperConnection,
    private val io: CoroutineDispatcher,
) {
    private val gate = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val subscribers = CopyOnWriteArrayList<AudioQueue>()

    @Volatile private var codec: MediaCodec? = null

    @Volatile private var running = false

    @Volatile private var configFrame: ByteArray? = null

    @Volatile private var inputs: Channel<Int>? = null

    private var pipe: ParcelFileDescriptor? = null
    private var reader: Job? = null
    private var pcm: Channel<Pcm>? = null
    private var headerJson: String = ""
    private var frameBytes: Int = OPUS_BYTES
    private var frameUs: Long = OPUS_US

    @Volatile private var silence = SilenceGate(0)

    /**
     * Joins Opus packets into 60 ms ones. Null for AAC, which WebCodecs takes one frame at a time.
     */
    @Volatile private var bundler: OpusBundler? = null

    suspend fun serve(session: DefaultWebSocketServerSession) {
        val queue = AudioQueue(CHANNEL_CAP)
        lease.opened()
        var attached = false
        try {
            val header = gate.withLock {
                subscribers.add(queue)
                attached = true
                // An encoder that failed, or whose capture ended, is gone while its closing
                // viewers are still detaching, so the count of viewers can't say.
                if (!running) {
                    withContext(io) {
                        stopEncoder()
                        startEncoder()
                    }
                }
                // The config may also arrive through emit; the queue keeps one.
                configFrame?.let(queue::offer)
                headerJson
            }
            session.send(Frame.Text(header))
            sendUntilClientLeaves(session) {
                while (true) {
                    val frame = queue.next() ?: break
                    session.send(Frame.Binary(true, frame))
                }
            }
        } finally {
            if (attached) withContext(NonCancellable) { detach(queue) }
            queue.close()
            lease.closed()
        }
    }

    private suspend fun detach(queue: AudioQueue) {
        gate.withLock {
            subscribers.remove(queue)
            if (subscribers.isEmpty()) withContext(io) { stopEncoder() }
        }
    }

    private suspend fun startEncoder() {
        val opened = openCodec()
        frameBytes = opened.frameBytes
        frameUs = opened.frameUs
        silence = SilenceGate(HANGOVER_FRAMES * opened.frameUs)
        bundler =
            if (opened.codecName == "opus") {
                OpusBundler { pts, packet -> emit(packFrame(FRAME_DELTA, pts, packet)) }
            } else {
                null
            }
        headerJson = audioHeader(opened.codecName, SAMPLE_RATE, CHANNELS)
        val encoder = opened.encoder
        val inputQueue = Channel<Int>(Channel.UNLIMITED)
        val pcmQueue = Channel<Pcm>(MAX_PENDING, BufferOverflow.DROP_OLDEST)
        inputs = inputQueue
        pcm = pcmQueue
        codec = encoder
        try {
            encoder.setCallback(callback(encoder))
            configure(encoder, opened.format)
            encoder.start()
            running = true
            val capture =
                helper.require().startAudioCapture(SAMPLE_RATE, CHANNELS)
                    ?: throw ApiException.unavailable(
                        "audio_error",
                        "The helper did not open audio capture. Remote submix needs Android 11 " +
                            "or later and a running shell helper.",
                    )
            pipe = capture
            reader = launchReader(encoder, capture, inputQueue, pcmQueue)
        } catch (e: CancellationException) {
            stopEncoder()
            throw e
        } catch (e: RemoteException) {
            stopEncoder()
            throw streamError(e)
        } catch (e: IllegalStateException) {
            stopEncoder()
            throw streamError(e)
        } catch (e: ApiException) {
            stopEncoder()
            throw e
        }
    }

    private fun launchReader(
        encoder: MediaCodec,
        capture: ParcelFileDescriptor,
        inputQueue: Channel<Int>,
        pcmQueue: Channel<Pcm>,
    ): Job =
        scope.launch(CoroutineName("phoneapi-pcm")) {
            val feeding = launch { feed(encoder, inputQueue, pcmQueue) }
            try {
                readPcm(capture, pcmQueue)
            } finally {
                pcmQueue.close()
                withContext(NonCancellable) { feeding.cancelAndJoin() }
            }
            if (running) {
                running = false
                closeViewers()
            }
        }

    private fun openCodec(): Opened {
        try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            return Opened(encoder, "opus", opusFormat(), OPUS_BYTES, OPUS_US)
        } catch (e: IOException) {
            Log.w(TAG, "opus encoder unavailable", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "opus encoder unavailable", e)
        }
        val encoder =
            try {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            } catch (e: IOException) {
                throw streamError(e)
            }
        return Opened(encoder, "aac", aacFormat(), AAC_BYTES, AAC_US)
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

    private fun callback(owner: MediaCodec): MediaCodec.Callback =
        object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                if (this@AudioStream.codec !== owner) return
                inputs?.trySend(index)
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                if (this@AudioStream.codec !== owner) {
                    releaseQuietly(owner, index)
                    return
                }
                handleOutput(owner, index, info)
            }

            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                if (this@AudioStream.codec !== owner) return
                Log.e(TAG, "audio encoder", error)
                scope.launch { failLive(owner) }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                if (this@AudioStream.codec !== owner) return
                val csd = format.getByteBuffer("csd-0") ?: return
                val view = csd.duplicate()
                val bytes = ByteArray(view.remaining())
                view.get(bytes)
                publishConfig(audioCodecDescription(bytes))
            }
        }

    private suspend fun failLive(owner: MediaCodec) {
        gate.withLock {
            if (codec !== owner) return@withLock
            withContext(io) { stopEncoder() }
            closeViewers()
        }
    }

    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val data =
            try {
                readOutput(codec, index, info)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "audio output", e)
                return
            } ?: return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            publishConfig(audioCodecDescription(data))
            return
        }
        val joiner = bundler
        if (!silence.keeps(info.presentationTimeUs)) {
            joiner?.flush()
            return
        }
        if (joiner != null) {
            joiner.add(info.presentationTimeUs, data)
        } else {
            emit(packFrame(FRAME_DELTA, info.presentationTimeUs, data))
        }
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

    private fun publishConfig(data: ByteArray) {
        if (!running || data.isEmpty()) return
        val packed = packFrame(FRAME_CONFIG, 0, data)
        configFrame = packed
        emit(packed)
    }

    private fun readPcm(capture: ParcelFileDescriptor, pcm: Channel<Pcm>) {
        val frame = ByteArray(frameBytes)
        val sounds = silence
        var pts = 0L
        try {
            ParcelFileDescriptor.AutoCloseInputStream(capture).use { input ->
                while (running && readFull(input, frame)) {
                    sounds.heard(frame, pts)
                    pcm.trySend(Pcm(frame.copyOf(), pts))
                    pts += frameUs
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "pcm ended", e)
        }
    }

    private fun readFull(input: InputStream, frame: ByteArray): Boolean {
        var offset = 0
        while (offset < frame.size) {
            val count = input.read(frame, offset, frame.size - offset)
            if (count < 0) return false
            offset += count
        }
        return true
    }

    private suspend fun feed(encoder: MediaCodec, inputs: Channel<Int>, pcm: Channel<Pcm>) {
        for (index in inputs) {
            val frame = pcm.receiveCatching().getOrNull() ?: return
            writeInput(encoder, index, frame.data, frame.pts)
        }
    }

    private fun writeInput(encoder: MediaCodec, index: Int, frame: ByteArray, pts: Long) {
        if (!running || codec !== encoder) return
        try {
            val buffer = encoder.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(frame)
            encoder.queueInputBuffer(index, 0, frame.size, pts, 0)
        } catch (e: IllegalStateException) {
            Log.i(TAG, "pcm queue", e)
        }
    }

    private fun emit(frame: ByteArray) {
        for (subscriber in subscribers) subscriber.offer(frame)
    }

    private fun closeViewers() {
        for (subscriber in subscribers) subscriber.close()
    }

    private suspend fun stopEncoder() {
        withContext(NonCancellable) {
            running = false
            val current = codec
            codec = null
            configFrame = null
            bundler = null
            val job = reader
            reader = null
            inputs?.close()
            inputs = null
            pcm?.close()
            pcm = null
            stopAudioQuietly()
            closePipe()
            job?.cancel()
            if (job != null && withTimeoutOrNull(JOIN_MS) { job.join() } == null) {
                Log.w(TAG, "pcm reader did not stop")
            }
            if (current != null) releaseEncoder(current)
        }
    }

    private fun stopAudioQuietly() {
        try {
            helper.getOrNull()?.stopAudioCapture()
        } catch (e: RemoteException) {
            Log.w(TAG, "stopAudio", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopAudio", e)
        }
    }

    private fun closePipe() {
        val end = pipe ?: return
        pipe = null
        try {
            end.close()
        } catch (e: IOException) {
            Log.i(TAG, "pcm close", e)
        }
    }

    private fun releaseEncoder(current: MediaCodec) {
        try {
            current.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "audio encoder stop", e)
        }
        current.release()
    }

    private fun releaseQuietly(codec: MediaCodec, index: Int) {
        try {
            codec.releaseOutputBuffer(index, false)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "audio release", e)
        }
    }

    private fun streamError(error: Throwable): ApiException =
        ApiException(
            502,
            "stream_error",
            error.message?.takeIf { it.isNotBlank() }
                ?: "The shell helper stopped during audio capture",
            cause = error,
        )

    // ByteArray equality is referential, so this stays a plain holder.
    @Suppress("UseDataClass") private class Pcm(val data: ByteArray, val pts: Long)

    private data class Opened(
        val encoder: MediaCodec,
        val codecName: String,
        val format: MediaFormat,
        val frameBytes: Int,
        val frameUs: Long,
    )

    private companion object {
        const val TAG = "PhoneApiStream"
        const val CHANNEL_CAP = 8
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val OPUS_BYTES = 960 * CHANNELS * 2
        const val OPUS_US = 20_000L
        const val AAC_BYTES = 1024 * CHANNELS * 2
        const val AAC_US = 1024L * 1_000_000L / SAMPLE_RATE
        const val JOIN_MS = 500L
        const val MAX_PENDING = 8
        const val BIT_RATE = 64_000

        /** Packets kept after the last sound: the encoder's lookahead, then one to fade out on. */
        const val HANGOVER_FRAMES = 3L

        fun opusFormat(): MediaFormat =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS)
                .apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                }

        fun aacFormat(): MediaFormat =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS)
                .apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                }
    }
}
