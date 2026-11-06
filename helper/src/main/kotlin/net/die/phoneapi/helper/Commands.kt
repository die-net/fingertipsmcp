package net.die.phoneapi.helper

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Shell commands and the pipes their output is handed back through. */
internal object Commands {
    private const val TAG = "PhoneApiHelper"
    private const val READ_JOIN_MS = 2_000L
    private const val NOT_FOUND = 127
    private const val TIMED_OUT = 124
    private const val BUFFER = 8 * 1024

    fun exec(argv: List<String>, timeoutMs: Long, maxOutputBytes: Int): ShellResult {
        val process =
            try {
                process(argv)
            } catch (e: IOException) {
                val reason = e.message ?: "could not start ${argv.first()}"
                return shellResult(NOT_FOUND, "", reason)
            }
        val stdout = readAsync(process.inputStream, maxOutputBytes)
        val stderr = readAsync(process.errorStream, maxOutputBytes)
        val finished =
            try {
                process.waitFor(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "exec interrupted", e)
                false
            }
        val exit =
            if (finished) {
                process.exitValue()
            } else {
                process.destroyForcibly()
                TIMED_OUT
            }
        return shellResult(exit, stdout.await(READ_JOIN_MS), stderr.await(READ_JOIN_MS))
    }

    private fun shellResult(exitCode: Int, stdout: String, stderr: String): ShellResult =
        ShellResult().apply {
            this.exitCode = exitCode
            this.stdout = stdout
            this.stderr = stderr
        }

    /** Runs [argv] and returns the read end of a pipe carrying its stdout. */
    fun pipe(argv: List<String>): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val process =
            try {
                process(argv)
            } catch (e: IOException) {
                pipe[0].close()
                pipe[1].close()
                throw e
            }
        thread(name = "helper-pipe", isDaemon = true) {
            try {
                process.inputStream.use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                        input.copyTo(out)
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "${argv.first()} pipe closed: ${e.message.orEmpty()}")
            } finally {
                process.destroy()
            }
        }
        return pipe[0]
    }

    private fun process(argv: List<String>): Process {
        val builder = ProcessBuilder(argv)
        if (builder.environment()["PATH"].isNullOrBlank()) {
            builder.environment()["PATH"] = "/system/bin:/system/xbin:/product/bin"
        }
        return builder.start()
    }

    /**
     * Reads [stream] to its end on a thread of its own, keeping the first [max] bytes and
     * discarding the rest. It must keep reading past the cap: a child whose pipe fills blocks in
     * write and never exits.
     */
    private fun readAsync(stream: InputStream, max: Int): Background<String> {
        val out = ByteArrayOutputStream()
        val cap = max.coerceAtLeast(0)
        val worker = thread(name = "helper-read", isDaemon = true) { drain(stream, cap, out) }
        return Background { timeoutMs ->
            worker.join(timeoutMs)
            // A reader still running (a background child holds the pipe open) returns what it
            // has.
            out.toByteArray().toString(Charsets.UTF_8)
        }
    }

    private fun drain(stream: InputStream, max: Int, out: ByteArrayOutputStream) {
        val buf = ByteArray(BUFFER)
        try {
            stream.use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    val keep = minOf(n, max - out.size())
                    if (keep > 0) out.write(buf, 0, keep)
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "output closed: ${e.message.orEmpty()}")
        }
    }

    private fun interface Background<T> {
        fun await(timeoutMs: Long): T
    }
}
