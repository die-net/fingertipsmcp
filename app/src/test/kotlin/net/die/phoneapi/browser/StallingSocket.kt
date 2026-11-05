package net.die.phoneapi.browser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Plays [replies], then reads nothing until closed, as a page that stopped answering. */
internal class StallingSocket(replies: ByteArray) : DevtoolsSocket {
    private val closed = CountDownLatch(1)
    private val played = ByteArrayInputStream(replies)
    override val output: OutputStream = ByteArrayOutputStream()
    override val input: InputStream =
        object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = played.read(b, off, len)
                if (n > 0) return n
                if (!closed.await(STALL_S, TimeUnit.SECONDS)) throw IOException("not closed")
                return -1
            }
        }

    override fun close() = closed.countDown()

    /** The CDP methods sent, in order. */
    fun methods(): List<String> = METHOD.findAll(sent()).map { it.groupValues[1] }.toList()

    /**
     * The CDP methods sent, once there are [count] of them. Replies are played before anything is
     * sent, so a call can return before its own message is written, and the last ones can lag.
     */
    fun awaitMethods(count: Int): List<String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STALL_S)
        while (true) {
            val sent = methods()
            if (sent.size >= count || System.nanoTime() > deadline) return sent
            Thread.sleep(POLL_MS)
        }
    }

    /** The text of every message sent, one per line. */
    fun sent(): String =
        unmasked(String((output as ByteArrayOutputStream).toByteArray(), Charsets.ISO_8859_1))

    private fun unmasked(text: String): String {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        var at = text.indexOf("\r\n\r\n") + HEADER_END
        val out = StringBuilder()
        while (true) {
            // The client may still be writing a frame, so stop at one that is not all here.
            val (start, length) = frameAt(bytes, at) ?: break
            val mask = bytes.copyOfRange(start - MASK_BYTES, start)
            for (i in 0 until length) {
                out.append((bytes[start + i].toInt() xor mask[i % MASK_BYTES].toInt()).toChar())
            }
            out.append('\n')
            at = start + length
        }
        return out.toString()
    }

    /** Where the payload of the client frame at [at] starts and its length, once it is all here. */
    private fun frameAt(bytes: ByteArray, at: Int): Pair<Int, Int>? {
        if (at + 2 > bytes.size) return null
        val short = bytes[at + 1].toInt() and LENGTH_BITS
        val extra =
            when (short) {
                LENGTH_16 -> 2
                LENGTH_64 -> 8
                else -> 0
            }
        if (at + 2 + extra > bytes.size) return null
        val length =
            if (extra == 0) {
                short
            } else {
                (0 until extra)
                    .fold(0L) { n, i -> (n shl 8) or bytes[at + 2 + i].toUByte().toLong() }
                    .toInt()
            }
        val start = at + 2 + extra + MASK_BYTES
        return if (start + length > bytes.size) null else start to length
    }
}

private const val STALL_S = 10L
private const val BYTE = 0xFF
private const val HEADER_END = 4
private const val LENGTH_BITS = 0x7F
private const val LENGTH_16 = 126
private const val LENGTH_64 = 127
private const val MASK_BYTES = 4
private val METHOD = Regex(""""method":"([^"]+)"""")

/**
 * A [StallingSocket] that answers the WebSocket handshake for whatever key the client sends, for
 * code that makes its own key, then plays [frames] and stalls.
 */
internal class KeyedSocket(private val frames: ByteArray) : DevtoolsSocket {
    private val closed = CountDownLatch(1)
    override val output = ByteArrayOutputStream()
    private var replies: ByteArray? = null
    private var at = 0
    override val input: InputStream =
        object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val bytes = replies ?: handshake().also { replies = it }
                if (at < bytes.size) {
                    val n = minOf(len, bytes.size - at)
                    bytes.copyInto(b, off, at, at + n)
                    at += n
                    return n
                }
                if (!closed.await(STALL_S, TimeUnit.SECONDS)) throw IOException("not closed")
                return -1
            }
        }

    /** The handshake reply, then [frames], once the client has sent its request. */
    private fun handshake(): ByteArray {
        var key: String? = null
        while (key == null) {
            val request = synchronized(output) { output.toString(Charsets.ISO_8859_1) }
            key = KEY_HEADER.find(request)?.groupValues?.get(1)
            if (key == null && closed.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                throw IOException("closed")
            }
        }
        val accept = "Sec-WebSocket-Accept: ${websocketAccept(key)}"
        return asciiLines("HTTP/1.1 101 Switching Protocols", accept) + frames
    }

    override fun close() = closed.countDown()
}

private const val POLL_MS = 10L
private val KEY_HEADER = Regex("""Sec-WebSocket-Key: (\S+)\r\n""")
