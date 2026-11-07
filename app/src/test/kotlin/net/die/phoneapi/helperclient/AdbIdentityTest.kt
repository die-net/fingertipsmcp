package net.die.phoneapi.helperclient

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AdbIdentityTest {
    @TempDir lateinit var dir: File

    @Test
    fun `remembers serial and port`() {
        val identity = AdbIdentity(dir)
        assertNull(identity.serial)
        identity.remember("ZY227DX4L7\n", 37_041)
        val reread = AdbIdentity(dir)
        assertEquals("ZY227DX4L7", reread.serial)
        assertEquals(37_041, reread.lastPort)
    }

    @Test
    fun `forgets only a refused port`() {
        val identity = AdbIdentity(dir)
        identity.remember("ZY227DX4L7", 37_041)
        identity.forgetPort(40_000)
        assertEquals(37_041, identity.lastPort)
        identity.forgetPort(37_041)
        assertNull(identity.lastPort)
        assertEquals("ZY227DX4L7", identity.serial)
    }

    @Test
    fun `an odd serial is not kept`() {
        val identity = AdbIdentity(dir)
        identity.remember("", 37_041)
        assertNull(identity.serial)
        identity.remember("no such property; or \$(rm)", 37_041)
        assertNull(identity.serial)
    }

    @Test
    fun `only own records match`() {
        assertTrue(isOwnAdbRecord("adb-ZY227DX4L7-AbC123", "ZY227DX4L7"))
        assertFalse(isOwnAdbRecord("adb-EMULATOR37X1X11X0-xYz789", "ZY227DX4L7"))
        // One serial that starts another must not match it.
        assertFalse(isOwnAdbRecord("adb-ZY227DX4L77-AbC123", "ZY227DX4L7"))
    }

    @Test
    fun `reads a record's serial`() {
        assertEquals("ZY227DX4L7", serialOfAdbRecord("adb-ZY227DX4L7-AbC123"))
        assertEquals("emulator-5554", serialOfAdbRecord("adb-emulator-5554-AbC123"))
        assertNull(serialOfAdbRecord("printer"))
    }

    @Test
    fun `a foreign record is skipped`() {
        val queue = ResolveQueue<String> { isOwnAdbRecord(it, "ZY227DX4L7") }
        queue.found("adb-EMULATOR37X1X11X0-xYz789", "emulator")
        queue.found("adb-ZY227DX4L7-AbC123", "phone")
        assertEquals("phone", queue.next())
        assertNull(queue.next())
    }

    @Test
    fun `a refound record is queued`() {
        val queue = ResolveQueue<String> { true }
        queue.found("adb-ZY227DX4L7-AbC123", "old port")
        assertEquals("old port", queue.next())
        queue.lost("adb-ZY227DX4L7-AbC123")
        queue.found("adb-ZY227DX4L7-AbC123", "new port")
        assertEquals("new port", queue.next())
    }

    @Test
    fun `a record is queued once`() {
        val queue = ResolveQueue<String> { true }
        queue.found("adb-ZY227DX4L7-AbC123", "first")
        queue.found("adb-ZY227DX4L7-AbC123", "again")
        assertEquals("first", queue.next())
        assertNull(queue.next())
    }
}
