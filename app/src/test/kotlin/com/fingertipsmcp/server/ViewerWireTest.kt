package com.fingertipsmcp.server

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.DisplayInfo
import com.fingertipsmcp.model.ViewerClipboard
import com.fingertipsmcp.model.ViewerError
import com.fingertipsmcp.model.ViewerHolder
import com.fingertipsmcp.model.ViewerLease
import com.fingertipsmcp.model.ViewerMessage
import com.fingertipsmcp.stream.VideoSpec
import com.fingertipsmcp.stream.audioHeader
import com.fingertipsmcp.stream.videoHeader
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The viewer page parses these frames, so their JSON stays as it was: `op` first, nulls left out.
 */
class ViewerWireTest {
    private fun json(message: ViewerMessage) =
        ApiJson.encodeToString(ViewerMessage.serializer(), message)

    @Test
    fun `lease messages`() {
        assertEquals(
            """{"op":"lease","holder":"you","heldMs":1200}""",
            json(ViewerLease(ViewerHolder.YOU, 1200)),
        )
        assertEquals(
            """{"op":"lease","holder":"person","heldMs":0,"takenFrom":"agent"}""",
            json(ViewerLease(ViewerHolder.PERSON, 0, ViewerHolder.AGENT)),
        )
    }

    @Test
    fun `clipboard and error messages`() {
        assertEquals(
            """{"op":"clipboard","id":7,"text":"hi","changed":true}""",
            json(ViewerClipboard(JsonPrimitive(7), text = "hi", changed = true)),
        )
        assertEquals(
            """{"op":"clipboard","image":"image/png","changed":false}""",
            json(ViewerClipboard(image = "image/png", changed = false)),
        )
        assertEquals(
            """{"op":"error","id":"a","message":"Bad key: x"}""",
            json(ViewerError(JsonPrimitive("a"), "Bad key: x")),
        )
    }

    @Test
    fun `stream headers`() {
        assertEquals(
            """{"codec":"avc","width":288,"height":640,"fps":30,"bitRate":4000000,""" +
                """"displayWidth":1080,"displayHeight":2400,"pointer":"touch"}""",
            videoHeader(
                288,
                640,
                VideoSpec(640, 30, 4_000_000),
                DisplayInfo(1080, 2400, 420, 0, 60f),
            ),
        )
        assertEquals(
            """{"codec":"opus","sampleRate":48000,"channels":2}""",
            audioHeader("opus", 48_000, 2),
        )
    }
}
