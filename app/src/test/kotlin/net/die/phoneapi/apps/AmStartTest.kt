package net.die.phoneapi.apps

import net.die.phoneapi.helperclient.ShellResult
import net.die.phoneapi.model.IntentRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmStartTest {
    @Test
    fun `carries the whole intent`() {
        val args =
            amStartArgs(
                IntentRequest(
                    data = "https://maps.google.com/?q=coffee&z=15",
                    packageName = "com.google.android.apps.maps",
                    categories = listOf("android.intent.category.BROWSABLE"),
                    extras = mapOf("note" to "two words"),
                    flags = 0x20000000,
                )
            )
        assertEquals(
            listOf(
                "am",
                "start",
                "-W",
                "-a",
                "android.intent.action.VIEW",
                "-d",
                "https://maps.google.com/?q=coffee&z=15",
                "-p",
                "com.google.android.apps.maps",
                "-c",
                "android.intent.category.BROWSABLE",
                "--es",
                "note",
                "two words",
                "-f",
                "536870912",
            ),
            args,
        )
    }

    @Test
    fun `leaves out what was not given`() {
        val request =
            IntentRequest(action = "android.intent.action.MAIN", component = "com.x/.Main")
        assertEquals(
            listOf("am", "start", "-W", "-a", "android.intent.action.MAIN", "-n", "com.x/.Main"),
            amStartArgs(request),
        )
    }

    @Test
    fun `reads the started activity`() {
        val started =
            parse(
                0,
                "Starting: Intent { act=android.intent.action.VIEW dat=https://example.com/... }",
                "Status: ok",
                "LaunchState: COLD",
                "Activity: com.android.chrome/com.google.android.apps.chrome.Main",
                "TotalTime: 812",
                "WaitTime: 820",
                "Complete",
            )
        assertNull(started.failure)
        assertEquals("com.android.chrome", started.activityPackage)
        assertNull(started.warning)
        assertFalse(started.chooser)
    }

    @Test
    fun `keeps a warning`() {
        val started =
            parse(
                0,
                "Starting: Intent { act=android.intent.action.VIEW dat=https://example.com/... }",
                "Warning: Activity not started, intent has been delivered to currently running " +
                    "top-most instance.",
                "Status: ok",
                "LaunchState: UNKNOWN (0)",
                "Activity: com.android.chrome/com.google.android.apps.chrome.Main",
                "WaitTime: 41",
                "Complete",
            )
        assertNull(started.failure)
        assertEquals(
            "Activity not started, intent has been delivered to currently running top-most " +
                "instance.",
            started.warning,
        )
    }

    @Test
    fun `unresolved exits zero`() {
        val started =
            parse(
                0,
                "Starting: Intent { act=android.intent.action.VIEW dat=nope:x }",
                "Error: Activity not started, unable to resolve Intent { act=android.intent.action" +
                    ".VIEW dat=nope:x flg=0x10000000 }",
            )
        assertEquals(AmFailure.UNRESOLVED, started.failure)
        assertTrue(started.message!!.startsWith("Activity not started, unable to resolve"))
    }

    @Test
    fun `a missing class is unresolved`() {
        val started =
            parse(
                0,
                "Starting: Intent { cmp=com.x/.Nope }",
                "Error type 3",
                "Error: Activity class {com.x/com.x.Nope} does not exist.",
            )
        assertEquals(AmFailure.UNRESOLVED, started.failure)
    }

    @Test
    fun `unexported is denied`() {
        val stderr =
            listOf(
                    "",
                    "Exception occurred while executing 'start':",
                    "java.lang.SecurityException: Permission Denial: starting Intent { " +
                        "cmp=com.x/.Hidden } from null (pid=1, uid=2000) not exported from uid " +
                        "10123",
                    "\tat com.android.server.wm.ActivityStackSupervisor.checkStartAnyActivity" +
                        "Permission(ActivityStackSupervisor.java:1043)",
                )
                .joinToString("\n")
        val started =
            parseAmStart(ShellResult(255, "Starting: Intent { cmp=com.x/.Hidden }\n", stderr))
        assertEquals(AmFailure.DENIED, started.failure)
        assertTrue(started.message!!.startsWith("java.lang.SecurityException: Permission Denial"))
    }

    @Test
    fun `failures map to codes`() {
        val request =
            IntentRequest(action = "android.intent.action.VIEW", component = "com.x/.Hidden")
        // A refusal is Android's, not a missing scope, so it is never 403 forbidden.
        val refused = intentFailed(request, AmFailure.DENIED, "not exported")
        assertEquals(409, refused.status)
        assertEquals("intent_refused", refused.error)
        assertEquals("not_found", intentFailed(request, AmFailure.UNRESOLVED, "").error)
        assertEquals("intent_failed", intentFailed(request, AmFailure.OTHER, "").error)
    }

    @Test
    fun `a timed out command fails`() {
        val started = parse(124, "Starting: Intent { act=x }")
        assertEquals(AmFailure.OTHER, started.failure)
        assertEquals("`am start` exited 124", started.message)
    }

    @Test
    fun `sees the chooser`() {
        val started =
            parse(
                0,
                "Status: ok",
                "Activity: android/com.android.internal.app.ResolverActivity",
                "Complete",
            )
        assertTrue(started.chooser)
        assertEquals("android", started.activityPackage)
    }

    private fun parse(exit: Int, vararg stdout: String): AmStart =
        parseAmStart(ShellResult(exit, stdout.joinToString("\n", postfix = "\n")))
}
