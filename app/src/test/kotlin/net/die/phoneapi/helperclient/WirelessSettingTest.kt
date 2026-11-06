package net.die.phoneapi.helperclient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WirelessSettingTest {
    /** The phone's value, which the user or Android can change behind PhoneAPI's back. */
    private var phone = false
    private val writes = mutableListOf<Boolean>()
    private val outside = mutableListOf<Boolean>()

    private fun setting(): WirelessSetting =
        WirelessSetting(
                read = { phone },
                write = {
                    phone = it
                    writes += it
                },
            )
            .also {
                it.onOutsideChange = { on -> outside += on }
            }

    @Test
    fun `own writes are not outside`() {
        val setting = setting()
        setting.set(true)
        assertTrue(setting.refresh())
        setting.set(false)
        assertEquals(listOf(true, false), writes)
        assertEquals(emptyList<Boolean>(), outside)
        assertFalse(setting.asLeft)
    }

    @Test
    fun `outside changes are kept`() {
        val setting = setting()
        phone = true
        assertTrue(setting.refresh())
        assertEquals(listOf(true), outside)
        assertTrue(setting.asLeft)
        assertTrue(setting.on.value)
        assertTrue(setting.refresh())
        assertEquals(listOf(true), outside)
    }

    @Test
    fun `restore puts back theirs`() {
        val setting = setting()
        setting.set(true)
        setting.restore()
        assertFalse(phone)
        phone = true
        setting.refresh()
        setting.set(true)
        setting.restore()
        assertTrue(phone)
        assertEquals(listOf(true, false), writes)
    }

    @Test
    fun `restore adopts a late change`() {
        val setting = setting()
        setting.set(true)
        // Android put it back to off, which the observer reported. Then the user allowed it, and
        // that report has not arrived yet.
        phone = false
        setting.refresh()
        phone = true
        setting.restore()
        assertTrue(phone)
        assertTrue(setting.asLeft)
        assertEquals(listOf(true), writes)
        assertEquals(listOf(false, true), outside)
    }

    @Test
    fun `the user's write is theirs`() {
        val setting = setting()
        setting.setByUser(true)
        assertTrue(setting.asLeft)
        assertEquals(emptyList<Boolean>(), outside)
        setting.set(false)
        setting.restore()
        assertTrue(phone)
    }

    @Test
    fun `a failed write changes nothing`() {
        val setting =
            WirelessSetting(read = { phone }, write = { throw SecurityException("no grant") })
        try {
            setting.set(true)
        } catch (_: SecurityException) {
            // The write is refused.
        }
        assertFalse(setting.on.value)
        assertFalse(setting.refresh())
    }
}
