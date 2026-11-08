package com.fingertipsmcp.browser

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.ApiException
import java.io.Closeable
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DomKeysTest {
    @Test
    fun `enter is a page key`() {
        val key = domKey("ENTER", 0)
        assertEquals("Enter", key.key)
        assertEquals(13, key.virtualKey)
        assertEquals("\r", key.text)
    }

    @Test
    fun `shift makes a letter upper`() {
        val key = domKey("KEYCODE_A", 1)
        assertEquals("A", key.key)
        assertEquals("A", key.text)
        assertEquals(setOf(CdpModifier.SHIFT), CdpModifier.fromMetaState(1))
    }

    @Test
    fun `home is not a page key`() {
        val error = assertThrows(ApiException::class.java) { domKey("HOME", 0) }
        assertEquals(400, error.status)
    }

    @Test
    fun `shift types, control does not`() = runTest {
        val page = KeyPage()
        dispatchDomKey(page, domKey("A", 1), CdpModifier.fromMetaState(1), longPress = false)
        dispatchDomKey(page, domKey("A", CTRL_ON), CdpModifier.fromMetaState(CTRL_ON), false)
        assertEquals(listOf("keyDown A 8", "keyUp A 8", "rawKeyDown a 2", "keyUp a 2"), page.keys)
    }

    @Test
    fun `backspace has no text`() {
        assertNull(domKey("DEL", 0).text)
    }

    /** A page that records the keys it is sent, with their text and modifiers. */
    private class KeyPage : CdpSession {
        val keys = ArrayList<String>()

        override fun listen(onEvent: (CdpEvent) -> Unit): Closeable = Closeable {}

        override suspend fun callCounted(method: String, params: JsonObject?): CdpReply {
            val sent = params ?: JsonObject(emptyMap())
            val result =
                when (method) {
                    "Page.getFrameTree" -> FRAME_TREE
                    "Page.createIsolatedWorld" -> buildJsonObject { put("executionContextId", 1) }
                    "Input.dispatchKeyEvent" -> {
                        val text = sent["text"]?.jsonPrimitive?.content
                        val type = sent["type"]!!.jsonPrimitive.content
                        val modifiers = sent["modifiers"]!!.jsonPrimitive.int
                        keys += "$type ${text ?: sent["key"]!!.jsonPrimitive.content} $modifiers"
                        JsonObject(emptyMap())
                    }
                    else -> JsonObject(emptyMap())
                }
            return CdpReply(result, eventsBefore = 0)
        }

        override fun post(method: String, params: JsonObject?) = Unit

        override suspend fun awaitClosed() = Unit
    }

    private companion object {
        const val CTRL_ON = 0x1000
        val FRAME_TREE =
            ApiJson.parseToJsonElement("""{"frameTree":{"frame":{"id":"main"}}}""").jsonObject
    }
}
