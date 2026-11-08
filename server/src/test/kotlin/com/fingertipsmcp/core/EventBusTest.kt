package com.fingertipsmcp.core

import com.fingertipsmcp.model.EventData
import com.fingertipsmcp.model.EventTypes
import com.fingertipsmcp.model.HelperStatus
import com.fingertipsmcp.model.HelperStatusData
import com.fingertipsmcp.model.ImeData
import com.fingertipsmcp.model.LogcatLine
import com.fingertipsmcp.model.ScreenTakenData
import com.fingertipsmcp.model.ToastData
import com.fingertipsmcp.model.UiChangedData
import com.fingertipsmcp.model.WindowChangedData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Event `data` keeps the shape clients already parse: absent values are left out. */
class EventBusTest {
    private val bus = EventBus()

    private fun data(type: String, payload: EventData?) = bus.next(type, payload).data.toString()

    @Test
    fun `empty data is an object`() {
        assertEquals("{}", data(EventTypes.SCREEN_ON, null))
    }

    @Test
    fun `keeps the wire names`() {
        assertEquals(
            """{"seq":7,"package":"com.example"}""",
            data(EventTypes.UI_CHANGED, UiChangedData(7, "com.example")),
        )
        assertEquals(
            """{"windowId":5,"package":"com.example","title":"Hi","className":"a.B","changes":["state"]}""",
            data(
                EventTypes.WINDOW_CHANGED,
                WindowChangedData(5, "com.example", "Hi", "a.B", listOf("state")),
            ),
        )
        assertEquals(
            """{"package":"kbd","left":1,"top":2,"right":3,"bottom":4}""",
            data(EventTypes.IME_SHOWN, ImeData("kbd", 1, 2, 3, 4)),
        )
        assertEquals(
            """{"text":"Saved","package":"com.example"}""",
            data(EventTypes.TOAST, ToastData("Saved", "com.example")),
        )
        assertEquals(
            """{"time":"10-01 04:07:12.123","pid":1,"tid":2,"level":"I","tag":"T","message":"m"}""",
            data(EventTypes.LOGCAT, LogcatLine("10-01 04:07:12.123", 1, 2, "I", "T", "m")),
        )
        assertEquals(
            """{"status":"needs_pairing"}""",
            data(EventTypes.HELPER_STATUS, HelperStatusData(HelperStatus.NEEDS_PAIRING)),
        )
        assertEquals(
            """{"session":"abc123","package":"com.example"}""",
            data(EventTypes.SCREEN_TAKEN, ScreenTakenData("abc123", "com.example")),
        )
    }

    @Test
    fun `leaves out missing values`() {
        assertEquals("{}", data(EventTypes.IME_HIDDEN, ImeData()))
        assertEquals("""{"text":"Saved"}""", data(EventTypes.TOAST, ToastData("Saved")))
        assertEquals(
            """{"windowId":5,"changes":["removed"]}""",
            data(EventTypes.WINDOW_CHANGED, WindowChangedData(5, changes = listOf("removed"))),
        )
    }
}
