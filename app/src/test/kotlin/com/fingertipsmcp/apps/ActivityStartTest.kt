package com.fingertipsmcp.apps

import android.content.Intent
import com.fingertipsmcp.model.IntentRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActivityStartTest {
    @Test
    fun `carries the whole intent`() {
        val intent =
            startIntent(
                IntentRequest(
                    data = "https://maps.google.com/?q=coffee&z=15",
                    packageName = "com.google.android.apps.maps",
                    component = "com.google.android.apps.maps/.Main",
                    categories = listOf("android.intent.category.BROWSABLE"),
                    extras = mapOf("note" to "two words"),
                    flags = 0x20000000,
                )
            )
        assertEquals("android.intent.action.VIEW", intent.action)
        assertEquals("https://maps.google.com/?q=coffee&z=15", intent.data?.toString())
        assertEquals("com.google.android.apps.maps", intent.`package`)
        assertEquals(
            "com.google.android.apps.maps/.Main",
            intent.component?.flattenToShortString(),
        )
        assertEquals(setOf("android.intent.category.BROWSABLE"), intent.categories)
        assertEquals(mapOf("note" to "two words"), intent.extras)
        assertEquals(0x20000000 or Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
    }

    @Test
    fun `leaves out what was not given`() {
        val intent = startIntent(IntentRequest(action = "android.intent.action.MAIN"))
        assertNull(intent.data)
        assertNull(intent.`package`)
        assertNull(intent.component)
        assertEquals(emptySet<String>(), intent.categories)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
    }

    @Test
    fun `reads the started activity`() {
        val started = ActivityStart(0, "com.android.chrome/com.google.android.apps.chrome.Main")
        assertNull(started.failure)
        assertEquals("com.android.chrome", started.activityPackage)
        assertNull(started.warning)
        assertFalse(started.chooser)
    }

    @Test
    fun `no new activity warns`() {
        for (code in listOf(1, 2, 3, 100)) {
            val started = ActivityStart(code)
            assertNull(started.failure, "code $code")
            assertTrue(started.warning.orEmpty().startsWith("Activity not started"), "code $code")
        }
    }

    @Test
    fun `no taker is unresolved`() {
        assertEquals(StartFailure.UNRESOLVED, ActivityStart(-91).failure)
        assertEquals(StartFailure.UNRESOLVED, ActivityStart(-92).failure)
    }

    @Test
    fun `a refusal keeps its reason`() {
        val reason = "Permission Denial: starting Intent { cmp=com.x/.Hidden } not exported"
        val started = ActivityStart(-94, refusal = reason)
        assertEquals(StartFailure.DENIED, started.failure)
        assertEquals(reason, started.message)
        assertEquals(StartFailure.DENIED, ActivityStart(-94).failure)
    }

    @Test
    fun `any other code fails`() {
        // Aborted, as a background start can be, and a code from a later Android. Neither says
        // what the words in an intent might, such as a package called permission.
        for (code in listOf(102, -96, -1, 150)) {
            val started = ActivityStart(code, "com.permission/.Main")
            assertEquals(StartFailure.OTHER, started.failure, "code $code")
            assertTrue("$code" in started.message)
        }
    }

    @Test
    fun `failures map to codes`() {
        // A refusal is Android's, not a missing scope, so it is never 403 forbidden.
        val refused = intentFailed("com.x/.Hidden", StartFailure.DENIED, "not exported")
        assertEquals(409, refused.status)
        assertEquals("intent_refused", refused.error)
        assertEquals("not_found", intentFailed("x", StartFailure.UNRESOLVED, "").error)
        assertEquals("intent_failed", intentFailed("x", StartFailure.OTHER, "").error)
    }

    @Test
    fun `a start that timed out fails`() {
        val started = ActivityStart(0, timedOut = true)
        assertEquals(StartFailure.OTHER, started.failure)
        assertNull(started.warning)
        assertEquals("Android did not finish starting it in time", started.message)
    }

    @Test
    fun `sees the chooser`() {
        val started = ActivityStart(0, "android/com.android.internal.app.ResolverActivity")
        assertTrue(started.chooser)
        assertEquals("android", started.activityPackage)
    }
}
