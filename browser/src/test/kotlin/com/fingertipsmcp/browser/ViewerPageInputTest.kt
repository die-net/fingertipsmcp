package com.fingertipsmcp.browser

import com.fingertipsmcp.model.Point
import java.io.Closeable
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A person's mouse and keyboard from the viewer, as Chrome receives them. */
class ViewerPageInputTest {
    private val page = Recorder()
    private var clock = 0L
    private val input = ViewerPageInput(page, devicePixelRatio = 1f) { clock }

    @Test
    fun `quick clicks double`() = runTest {
        input.press(Point(10f, 10f))
        input.release(Point(10f, 10f))
        clock = 200
        input.press(Point(12f, 11f))
        input.release(Point(12f, 11f))
        clock = 2_000
        input.press(Point(12f, 11f))
        assertEquals(listOf(1, 1, 2, 2, 1), page.clicks())
    }

    @Test
    fun `typing shifts capitals`() = runTest {
        input.type("Hi!")
        assertEquals(
            listOf(
                "rawKeyDown Shift",
                "keyDown H",
                "keyUp H",
                "keyUp Shift",
                "keyDown i",
                "keyUp i",
                "rawKeyDown Shift",
                "keyDown !",
                "keyUp !",
                "keyUp Shift",
            ),
            page.keys(),
        )
    }

    @Test
    fun `keyless text is inserted`() = runTest {
        input.type("a😀b")
        assertEquals(listOf("keyDown a", "keyUp a", "insert 😀", "keyDown b", "keyUp b"), page.log)
    }

    /** A page that records the input events it is sent, and answers nothing else. */
    private class Recorder : CdpSession {
        val log = ArrayList<String>()
        private val mouse = ArrayList<JsonObject>()

        fun clicks(): List<Int> =
            mouse
                .filter { it["type"]!!.jsonPrimitive.content != "mouseMoved" }
                .map { it["clickCount"]!!.jsonPrimitive.int }

        fun keys(): List<String> = log.filter { !it.startsWith("insert") }

        override fun listen(onEvent: (CdpEvent) -> Unit): Closeable = Closeable {}

        override suspend fun callCounted(method: String, params: JsonObject?): CdpReply {
            val sent = params ?: JsonObject(emptyMap())
            when (method) {
                "Input.dispatchMouseEvent" -> mouse += sent
                "Input.dispatchKeyEvent" ->
                    log +=
                        sent["type"]!!.jsonPrimitive.content +
                            " " +
                            sent["key"]!!.jsonPrimitive.content
                "Input.insertText" -> log += "insert " + sent["text"]!!.jsonPrimitive.content
            }
            return CdpReply(JsonObject(emptyMap()), eventsBefore = 0)
        }

        override fun post(method: String, params: JsonObject?) = Unit

        override suspend fun awaitClosed() = Unit
    }
}
