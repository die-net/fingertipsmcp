package net.die.phoneapi.tree

import net.die.phoneapi.model.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HoldSyncTest {
    /** A helper that records the holds it was sent, and fails while [down]. */
    private class FakeHelper(var down: Boolean = false) {
        val sent = ArrayList<Boolean>()

        fun setHold(hold: Boolean) {
            if (down) throw ApiException.helperUnavailable("It is starting.")
            sent += hold
        }
    }

    private val failures = ArrayList<Boolean>()
    private val sync =
        HoldSync<FakeHelper>(send = { helper, hold -> helper.setHold(hold) }) { hold, _ ->
            failures += hold
        }

    @Test
    fun `sends only changes`() {
        val helper = FakeHelper()
        sync.want(true) { helper }
        sync.want(true) { helper }
        sync.want(false) { helper }
        assertEquals(listOf(true, false), helper.sent)
    }

    @Test
    fun `a failed hold is resent`() {
        val helper = FakeHelper(down = true)
        sync.want(true) { helper }
        assertEquals(listOf(true), failures)
        helper.down = false
        sync.attached(helper)
        assertEquals(listOf(true), helper.sent)
    }

    @Test
    fun `a hold reaches the next helper`() {
        sync.want(true) { throw ApiException.helperUnavailable("It is starting.") }
        val next = FakeHelper()
        sync.attached(next)
        assertEquals(listOf(true), next.sent)
    }

    @Test
    fun `a release is not lost`() {
        val helper = FakeHelper()
        sync.want(true) { helper }
        helper.down = true
        sync.want(false) { helper }
        helper.down = false
        sync.attached(helper)
        assertEquals(listOf(true, false), helper.sent)
    }
}
