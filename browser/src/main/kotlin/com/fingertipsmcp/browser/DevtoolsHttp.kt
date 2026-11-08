package com.fingertipsmcp.browser

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** One DevTools HTTP GET, used for `/json/list` and `/json/version`. */
fun httpGet(input: InputStream, output: OutputStream, path: String, maxBody: Int): String {
    output.write(httpRequest(path))
    output.flush()
    val (status, headers) = input.readHttpHeaders()
    if (status != HTTP_OK) throw IOException("DevTools list returned HTTP $status")
    return input.readBody(headers, maxBody).decodeToString()
}

/**
 * Writes the opening GET and checks the 101 and `Sec-WebSocket-Accept`. Bytes after the header
 * block stay in [input] for the WebSocket.
 */
internal fun handshakeWebSocket(
    input: InputStream,
    output: OutputStream,
    path: String,
    newKey: () -> String = ::websocketKey,
) {
    val key = newKey()
    output.write(websocketRequest(path, key))
    output.flush()
    val (status, headers) = input.readHttpHeaders()
    if (status != SWITCHING_PROTOCOLS) {
        // Chrome says why in a short body, such as "No such target id: ..." for a closed tab.
        val why = runCatching { input.readBody(headers, MAX_REFUSAL).decodeToString().trim() }
        throw WebSocketRefused(status, why.getOrNull().orEmpty())
    }
    if (headers["sec-websocket-accept"] != websocketAccept(key)) {
        throw IOException("DevTools WebSocket accept key did not match")
    }
}

/**
 * DevTools answered the WebSocket upgrade with [status] instead of 101, saying why in [detail]. It
 * is the cause of the `cdp_error` a caller sees.
 */
internal class WebSocketRefused(val status: Int, val detail: String) :
    IOException(
        "DevTools WebSocket was refused ($status)" + if (detail.isEmpty()) "" else ": $detail"
    ) {
    /**
     * Whether the refusal means the target has closed. Chrome 133 answers 500 "No such target id";
     * some releases answer 404. It sends no code of its own, so the body is all there is to read.
     */
    val targetGone: Boolean
        get() = status == HTTP_NOT_FOUND || detail.startsWith(NO_TARGET)
}

internal fun websocketKey(): String {
    val bytes = ByteArray(KEY_BYTES)
    SecureRandom().nextBytes(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}

internal fun websocketAccept(key: String): String {
    val digest =
        MessageDigest.getInstance("SHA-1")
            .digest((key + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII))
    return Base64.getEncoder().encodeToString(digest)
}

internal fun asciiLines(vararg lines: String): ByteArray {
    val out = ByteArrayOutputStream()
    for (line in lines) {
        out.write(line.toByteArray(Charsets.US_ASCII))
        out.write(CR)
        out.write(LF)
    }
    out.write(CR)
    out.write(LF)
    return out.toByteArray()
}

private fun InputStream.readHttpHeaders(): Pair<Int, Map<String, String>> {
    val lines = readHeaderBlock().split(EOL)
    val status =
        lines.firstOrNull()?.substringAfter(' ')?.substringBefore(' ')?.toIntOrNull()
            ?: throw IOException("DevTools sent no HTTP status")
    val headers = HashMap<String, String>()
    for (line in lines.drop(1)) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
    }
    return status to headers
}

private fun httpRequest(path: String): ByteArray =
    asciiLines("GET $path HTTP/1.1", "Host: localhost", "Connection: close")

private fun websocketRequest(path: String, key: String): ByteArray =
    asciiLines(
        "GET $path HTTP/1.1",
        "Host: localhost",
        "Upgrade: websocket",
        "Connection: Upgrade",
        "Sec-WebSocket-Key: $key",
        "Sec-WebSocket-Version: 13",
    )

private fun InputStream.readBody(headers: Map<String, String>, maxBody: Int): ByteArray {
    if (headers.containsKey("content-length")) {
        val length =
            headers.getValue("content-length").toIntOrNull()?.takeIf { it >= 0 }
                ?: throw IOException("DevTools sent a bad Content-Length")
        if (length > maxBody) throw IOException("DevTools response is too large")
        return readExact(length)
    }
    return readToEnd(maxBody)
}

private fun InputStream.readHeaderBlock(): String {
    val out = ByteArrayOutputStream()
    var matched = 0
    while (matched < HEADER_END.size) {
        if (out.size() >= MAX_HEADER) throw IOException("DevTools HTTP header is too large")
        val value = read()
        if (value < 0) throw EOFException("DevTools closed before the HTTP header finished")
        out.write(value)
        matched =
            if (value == HEADER_END[matched].toInt()) {
                matched + 1
            } else if (value == HEADER_END[0].toInt()) {
                1
            } else {
                0
            }
    }
    return out.toByteArray().decodeToString().removeSuffix(EOL + EOL)
}

private fun InputStream.readToEnd(maxBody: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(CHUNK)
    while (out.size() < maxBody) {
        val n = read(buf, 0, minOf(buf.size, maxBody - out.size()))
        if (n < 0) return out.toByteArray()
        if (n > 0) out.write(buf, 0, n)
    }
    if (read() >= 0) throw IOException("DevTools response is too large")
    return out.toByteArray()
}

private fun InputStream.readExact(count: Int): ByteArray {
    if (count == 0) return ByteArray(0)
    val buf = ByteArray(count)
    var off = 0
    while (off < count) {
        val n = read(buf, off, count - off)
        if (n < 0) throw EOFException("DevTools closed after $off of $count bytes")
        off += n
    }
    return buf
}

private val EOL = charArrayOf('\r', '\n').concatToString()
private const val CR = '\r'.code
private const val LF = '\n'.code
private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404
private const val NO_TARGET = "No such target id"
private const val SWITCHING_PROTOCOLS = 101
private const val KEY_BYTES = 16
private const val MAX_HEADER = 16 * 1024
private const val MAX_REFUSAL = 512
private const val CHUNK = 8 * 1024
private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

private val HEADER_END =
    byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
