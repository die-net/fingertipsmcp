package net.die.phoneapi.helperclient

import android.app.NotificationManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationsTest {
    @Test
    fun `every limiting filter is dnd`() {
        assertTrue(isDoNotDisturb(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertTrue(isDoNotDisturb(NotificationManager.INTERRUPTION_FILTER_ALARMS))
        assertTrue(isDoNotDisturb(NotificationManager.INTERRUPTION_FILTER_NONE))
    }

    @Test
    fun `all and unknown are not dnd`() {
        assertFalse(isDoNotDisturb(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertFalse(isDoNotDisturb(NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
    }
}
