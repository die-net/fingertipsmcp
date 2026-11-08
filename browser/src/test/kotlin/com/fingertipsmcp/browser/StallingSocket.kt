package com.fingertipsmcp.browser

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A DevTools socket whose peer plays [script] the way Chrome would, answering only what the client
 * asks. The script is what Chrome sends: an HTTP response header, then, after a 101, the server's
 * WebSocket frames.
 *
 * The header goes out once the client has written its request. A frame with an `id` goes out once
 * the client has sent the command with that id, and the frames after it without one, such as
 * events, follow it. An event that Chrome sends only because of a command, before answering it,
 * names that command in an `afterId` field and waits for it the same way. So a call cannot return
 * before its command is written, and a reply nothing asked for is never read. After the script the
 * peer sends nothing until the socket closes, as a page that stopped answering, or until [send]
 * delivers what the page did on its own. A response other than a 101 ends with the connection
 * closing, as DevTools' HTTP replies do.
 *
 * With a [clock], a read waiting for a command counts as parked there, so the clock can move time
 * on while it waits.
 */
internal open class StallingSocket(
    script: ByteArray,
    private val clock: VirtualIo? = null,
    override val timedOut: Boolean = false,
) : DevtoolsSocket {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val parts = ArrayDeque(parts(script))
    private val closesAfterScript = !parts.isEmpty() && status(script) != SWITCHING_PROTOCOLS
    private var readable = ByteArray(0)
    private var readAt = 0
    private var ended = false
    private var closed = false
    private var waiting = 0
    private var letGo = 0L

    private val client = ByteArrayOutputStream()
    private var clientAt = 0
    private var requestWritten = false
    private var clientKey: String? = null
    private val messages = ArrayList<String>()
    private val commandIds = HashSet<Int>()

    override val input: InputStream =
        object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int = readInto(b, off, len)
        }

    override val output: ClientOutput = ClientOutput()

    /** What the client writes, which the peer reads as it comes. */
    inner class ClientOutput : OutputStream() {
        /** Every byte the client has written, handshake included. */
        fun toByteArray(): ByteArray = lock.withLock { client.toByteArray() }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            lock.withLock {
                if (closed) return
                client.write(b, off, len)
                readClient()
                release()
            }
        }
    }

    override fun close() = lock.withLock {
        closed = true
        letWaitersGo()
    }

    /** Sends [frames] now, as Chrome does with an event no command asked for. */
    fun send(frames: ByteArray) = lock.withLock {
        readable += frames
        letWaitersGo()
    }

    /** The CDP methods sent, in order. */
    fun methods(): List<String> = lock.withLock {
        messages.mapNotNull { METHOD.find(it)?.groupValues?.get(1) }
    }

    /** The CDP methods sent, once there are [count] of them, or after a few seconds. */
    fun awaitMethods(count: Int): List<String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STALL_S)
        lock.withLock {
            while (messages.size < count) {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) break
                changed.await(left, TimeUnit.MILLISECONDS)
            }
        }
        return methods()
    }

    /** The text of every message sent, one per line. */
    fun sent(): String = lock.withLock { messages.joinToString("") { "$it\n" } }

    /** Chrome's handshake reply. A [KeyedSocket] answers it for the key the client sent. */
    protected open fun header(scripted: ByteArray, key: String?): ByteArray = scripted

    private fun readInto(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STALL_S)
        lock.withLock {
            while (readAt == readable.size && !ended && !closed) awaitPeer(deadline)
            if (readAt == readable.size) return -1
            val n = minOf(len, readable.size - readAt)
            readable.copyInto(b, off, readAt, readAt + n)
            readAt += n
            return n
        }
    }

    /** Waits, holding [lock], until the peer sends or closes, parked on [clock] meanwhile. */
    private fun awaitPeer(deadline: Long) {
        val mark = letGo
        waiting++
        clock?.park()
        changed.await(
            TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1),
            TimeUnit.MILLISECONDS,
        )
        if (letGo != mark) return
        // Nothing let this read go, so it still counts itself.
        waiting--
        clock?.unpark(1)
        if (System.nanoTime() > deadline) throw IOException("not closed")
    }

    /** Sends every part the client has asked for, in order. Called holding [lock]. */
    private fun release() {
        var sent = false
        while (parts.firstOrNull()?.asked(requestWritten, commandIds) == true) {
            val part = parts.removeFirst()
            val bytes = if (part.gate == Gate.Request) header(part.bytes, clientKey) else part.bytes
            readable += bytes
            sent = true
        }
        if (parts.isEmpty() && closesAfterScript && requestWritten) {
            ended = true
            sent = true
        }
        if (sent) letWaitersGo()
    }

    private fun letWaitersGo() {
        letGo++
        clock?.unpark(waiting)
        waiting = 0
        changed.signalAll()
    }

    /** Reads the request header, then each whole masked frame the client has written. */
    private fun readClient() {
        val bytes = client.toByteArray()
        if (!requestWritten) {
            val end = String(bytes, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            if (end < 0) return
            val request = String(bytes, 0, end, Charsets.ISO_8859_1)
            clientKey = KEY_HEADER.find("$request\r\n")?.groupValues?.get(1)
            requestWritten = true
            clientAt = end + HEADER_END
        }
        var frame = frame(bytes, clientAt)
        while (frame != null) {
            clientAt = frame.end
            val text = frame.text()
            if (text != null) {
                messages += text
                idOf(text)?.let(commandIds::add)
            }
            frame = frame(bytes, clientAt)
        }
    }

    private fun parts(script: ByteArray): List<Part> {
        if (script.isEmpty()) return emptyList()
        val end = String(script, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
        if (end < 0) return listOf(Part(script, Gate.Request))
        val headerEnd = end + HEADER_END
        if (status(script) != SWITCHING_PROTOCOLS) return listOf(Part(script, Gate.Request))
        val out = mutableListOf(Part(script.copyOfRange(0, headerEnd), Gate.Request))
        var at = headerEnd
        var frame = frame(script, at)
        while (frame != null) {
            val id = frame.text()?.let { idOf(it) ?: idOf(it, "afterId") }
            out +=
                Part(script.copyOfRange(at, frame.end), id?.let { Gate.Command(it) } ?: Gate.None)
            at = frame.end
            frame = frame(script, at)
        }
        // Bytes that are not a whole frame go as they are.
        if (at < script.size) out += Part(script.copyOfRange(at, script.size), Gate.None)
        return out
    }

    private sealed interface Gate {
        /** Once the client has written its HTTP request. */
        data object Request : Gate

        /** Once the client has sent the command with this id. */
        data class Command(val id: Int) : Gate

        /** Right after the part before it. */
        data object None : Gate
    }

    private class Part(val bytes: ByteArray, val gate: Gate) {
        /** Whether the client has asked for this part. */
        fun asked(requestWritten: Boolean, commandIds: Set<Int>): Boolean =
            when (gate) {
                Gate.Request -> requestWritten
                is Gate.Command -> gate.id in commandIds
                Gate.None -> true
            }
    }

    private class Frame(private val opcode: Int, private val payload: ByteArray, val end: Int) {
        /** The text of a text frame, or null for any other. */
        fun text(): String? = if (opcode == TEXT) payload.decodeToString() else null
    }

    private companion object {
        const val STALL_S = 10L
        const val BYTE = 0xFF
        const val HEADER_END = 4
        const val OPCODE_BITS = 0x0F
        const val LENGTH_BITS = 0x7F
        const val MASK_BIT = 0x80
        const val LENGTH_16 = 126
        const val LENGTH_64 = 127
        const val MASK_BYTES = 4
        const val TEXT = 1
        const val SWITCHING_PROTOCOLS = 101
        val METHOD = Regex(""""method":"([^"]+)"""")
        val KEY_HEADER = Regex("""Sec-WebSocket-Key: (\S+)\r\n""")

        fun status(script: ByteArray): Int? =
            String(script, Charsets.ISO_8859_1)
                .substringBefore("\r\n")
                .substringAfter(' ')
                .substringBefore(' ')
                .toIntOrNull()

        fun idOf(text: String, field: String = "id"): Int? =
            try {
                ((Json.parseToJsonElement(text) as? JsonObject)?.get(field) as? JsonPrimitive)
                    ?.intOrNull
            } catch (_: IllegalArgumentException) {
                null
            }

        /** The frame at [at], unmasked, once it is all there. */
        fun frame(bytes: ByteArray, at: Int): Frame? {
            if (at + 2 > bytes.size) return null
            val opcode = bytes[at].toInt() and OPCODE_BITS
            val masked = (bytes[at + 1].toInt() and MASK_BIT) != 0
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
            val maskAt = at + 2 + extra
            val start = maskAt + if (masked) MASK_BYTES else 0
            if (start + length > bytes.size) return null
            val payload =
                ByteArray(length) { i ->
                    val b = bytes[start + i].toInt()
                    if (masked) (b xor bytes[maskAt + i % MASK_BYTES].toInt()).toByte()
                    else b.toByte()
                }
            return Frame(opcode, payload, start + length)
        }
    }
}

/**
 * A [StallingSocket] that answers the WebSocket handshake for whatever key the client sends, for
 * code that makes its own key, then plays [frames] as the client asks for them.
 */
internal class KeyedSocket(frames: ByteArray, clock: VirtualIo? = null) :
    StallingSocket(asciiLines("HTTP/1.1 101 Switching Protocols") + frames, clock) {
    override fun header(scripted: ByteArray, key: String?): ByteArray =
        asciiLines(
            "HTTP/1.1 101 Switching Protocols",
            "Sec-WebSocket-Accept: ${websocketAccept(key.orEmpty())}",
        )
}
