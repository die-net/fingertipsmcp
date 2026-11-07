package net.die.phoneapi.helper.compat

import android.annotation.SuppressLint
import android.app.Application
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.die.phoneapi.helper.HelperDaemon

/**
 * Records device audio through [MediaRecorder.AudioSource.REMOTE_SUBMIX] and writes PCM s16le to a
 * pipe the app encodes. The source is a public constant; opening it requires
 * [android.Manifest.permission.CAPTURE_AUDIO_OUTPUT], which the shell uid holds and the app does
 * not.
 */
internal class AudioTap {
    private val lock = Any()
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var writeEnd: ParcelFileDescriptor? = null
    private val tasks by lazy { TopTask() }
    private val activities by lazy { ActivityStarter() }

    @Volatile private var running = false

    @Volatile private var generation = 0

    @Suppress("MissingUseCall") // The app owns the read end of this pipe.
    fun start(sampleRate: Int, channels: Int): ParcelFileDescriptor =
        synchronized(lock) {
            val token = ++generation
            releaseLocked()
            require(sampleRate in SAMPLE_RATE_RANGE) { "sampleRate $sampleRate" }
            require(channels == 1 || channels == 2) { "channels $channels" }
            val mask =
                if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minimum =
                AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "No audio buffer for $sampleRate Hz" }
            // Android 11 only allows this capture while the shell uid is in the foreground, and
            // AudioRecord names a process with no application "uid:<n>", which AppOps rejects.
            val shellTask = bringShellForward()
            try {
                useShellPackage()
                val audio = newRecord(mask, sampleRate, minimum * 2)
                if (audio.state != AudioRecord.STATE_INITIALIZED) {
                    audio.release()
                    error("AudioRecord did not initialize")
                }
                val pipe = ParcelFileDescriptor.createPipe()
                audio.startRecording()
                if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    audio.release()
                    error("AudioRecord did not start")
                }
                running = true
                record = audio
                writeEnd = pipe[1]
                thread =
                    Thread({ pump(audio, pipe[1], minimum, token) }, "phoneapi-audio").also {
                        it.isDaemon = true
                        it.start()
                    }
                pipe[0]
            } finally {
                shellTask?.let(::dismissShellActivity)
            }
        }

    fun stop() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        running = false
        writeEnd?.let { end ->
            try {
                end.close()
            } catch (e: IOException) {
                Log.i(TAG, "audio pipe close", e)
            }
        }
        writeEnd = null
        record?.let { audio ->
            try {
                audio.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "audio stop", e)
            }
            audio.release()
        }
        record = null
        thread?.join(JOIN_MS)
        thread = null
    }

    // REMOTE_SUBMIX is public, but setAudioSource's IntDef omits it. Capturing it requires
    // CAPTURE_AUDIO_OUTPUT, which the shell uid holds; this process never requests RECORD_AUDIO.
    @SuppressLint("MissingPermission", "WrongConstant")
    private fun newRecord(mask: Int, sampleRate: Int, bufferBytes: Int): AudioRecord =
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()

    private fun pump(audio: AudioRecord, end: ParcelFileDescriptor, chunk: Int, token: Int) {
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(end).use { output ->
                copyAudio(audio, output, ByteArray(chunk), token)
            }
        } catch (e: IOException) {
            Log.i(TAG, "audio reader stopped", e)
        } catch (e: IllegalStateException) {
            Log.i(TAG, "audio reader stopped", e)
        } catch (e: InterruptedException) {
            Log.i(TAG, "audio reader stopped", e)
        }
    }

    // token rejects a pump that outlives join() once start() has replaced it.
    private fun copyAudio(
        audio: AudioRecord,
        output: OutputStream,
        buffer: ByteArray,
        token: Int,
    ) {
        var loggedEmpty = false
        while (running && token == generation) {
            val read = audio.read(buffer, 0, buffer.size)
            if (read < 0) return
            if (read == 0) {
                // An idle remote-submix track returns immediately. Sleep instead of spinning.
                if (!loggedEmpty) Log.w(TAG, "audio read returned no samples")
                loggedEmpty = true
                Thread.sleep(EMPTY_READ_MS)
            } else {
                output.write(buffer, 0, read)
            }
        }
    }

    /**
     * On Android 11 the shell uid is treated as cached, and remote-submix capture then never leaves
     * standby. Marking this process important, and briefly opening the shell heap-dump activity
     * where that activity exists, is what lets [AudioRecord.startRecording] attach. Which of the
     * two steps is needed has not been measured. Returns the task of the activity it opened, so the
     * caller closes that task and nothing else of the shell package's.
     */
    private fun bringShellForward(): Int? {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.R) return null
        markProcessImportant()
        return startShellActivity()
    }

    /**
     * [AudioRecord] names its caller while it is constructed. Android 11 and 12 read
     * [android.app.ActivityThread.currentOpPackageName], and Android 13 and later ask the current
     * application for its attribution source. A process with no application is named `uid:<n>`, and
     * AppOps refuses that name, so this installs an application that answers as the shell package.
     * The activity thread's handler has to be created on the helper looper.
     */
    private fun useShellPackage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val looper = HelperDaemon.looper()
        if (Looper.myLooper() == looper) {
            installShellPackage()
            return
        }
        val failure = AtomicReference<ReflectiveOperationException>()
        val done = CountDownLatch(1)
        Handler(looper).post {
            try {
                installShellPackage()
            } catch (e: ReflectiveOperationException) {
                failure.set(e)
            }
            done.countDown()
        }
        if (!done.await(PACKAGE_WAIT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "shell package timed out")
            return
        }
        failure.get()?.let { Log.w(TAG, "shell package", it) }
    }

    private fun installShellPackage() {
        val threadClass = Class.forName("android.app.ActivityThread")
        var thread = threadClass.getMethod("currentActivityThread").invoke(null)
        if (thread == null) {
            val constructor = threadClass.getDeclaredConstructor()
            constructor.isAccessible = true
            thread = constructor.newInstance()
            val current = threadClass.getDeclaredField("sCurrentActivityThread")
            current.isAccessible = true
            current.set(null, thread)
        }
        val app = Application()
        val attach =
            ContextWrapper::class
                .java
                .getDeclaredMethod(
                    "attachBaseContext",
                    Context::class.java,
                )
        attach.isAccessible = true
        attach.invoke(app, ShellContext())
        val field = threadClass.getDeclaredField("mInitialApplication")
        field.isAccessible = true
        field.set(thread, app)
    }

    private fun markProcessImportant() {
        try {
            val manager = Class.forName("android.app.ActivityManager")
            val service = manager.getDeclaredMethod("getService").invoke(null) ?: return
            val method =
                service.javaClass.getMethod(
                    "setProcessImportant",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    String::class.java,
                )
            method.invoke(service, Binder(), Process.myPid(), true, "phoneapi-audio")
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "audio importance", e)
        }
    }

    /**
     * Starts the shell heap-dump activity and returns its task once it is on top, or null. The wait
     * ends when the launch has completed, so the activity is resumed by then. A cold start of its
     * process took 2 s on an emulator, so the limit is the one app starts have, which matters only
     * when Android holds the wait open. `am` adds the new task flag itself, so it is added here.
     */
    private fun startShellActivity(): Int? {
        val component = ComponentName(SHELL_PACKAGE, HEAP_DUMP_ACTIVITY)
        val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return shellActivityTask(
            start = { activities.start(intent, START_WAIT_MS) },
            taskShowing = { tasks.taskShowing(component) },
        )
    }

    /** Closes only the task [startShellActivity] opened, not the rest of the shell package. */
    private fun dismissShellActivity(task: Int) {
        try {
            if (!tasks.removeTask(task)) Log.w(TAG, "The shell activity's task was already gone")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not close the shell activity", e)
        }
    }

    /**
     * The base context of the application [installShellPackage] installs. It has no base of its
     * own, so every method [AudioRecord] reaches through it is overridden here.
     */
    private class ShellContext : ContextWrapper(null) {
        override fun getPackageName(): String = SHELL_PACKAGE

        override fun getOpPackageName(): String = SHELL_PACKAGE

        override fun getAttributionSource(): AttributionSource {
            // Android 11 has no such method, so only Android 12 and later call it.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                throw UnsupportedOperationException("No AttributionSource before Android 12")
            }
            return AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
        }
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val JOIN_MS = 500L
        const val EMPTY_READ_MS = 20L
        const val HEAP_DUMP_ACTIVITY = "com.android.shell.HeapDumpActivity"
        const val START_WAIT_MS = 20_000L
        const val PACKAGE_WAIT_SECONDS = 2L
        const val SHELL_PACKAGE = "com.android.shell"
        val SAMPLE_RATE_RANGE = 8_000..96_000
    }
}
