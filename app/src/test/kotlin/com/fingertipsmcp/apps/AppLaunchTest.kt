package com.fingertipsmcp.apps

import android.content.ContextWrapper
import android.content.Intent
import android.os.IBinder
import com.fingertipsmcp.core.EventBus
import com.fingertipsmcp.helper.IHelper
import com.fingertipsmcp.helper.Registration
import com.fingertipsmcp.helper.StartResult
import com.fingertipsmcp.helperclient.HelperConnection
import com.fingertipsmcp.helperclient.HelperShell
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.HelperStatus
import com.fingertipsmcp.model.LaunchRequest
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** Launching an app with the helper up, and without it from in front and from behind. */
@OptIn(ExperimentalCoroutinesApi::class) // currentTime reads the virtual clock.
class AppLaunchTest {
    /** A context that records the starts it is asked for. Android may drop them, without a word. */
    private class RecordingContext : ContextWrapper(null) {
        val started = ArrayList<Intent>()

        override fun startActivity(intent: Intent) {
            started += intent
        }
    }

    private val context = RecordingContext()
    private val dispatcher = StandardTestDispatcher()
    private val helper = HelperConnection(EventBus(), HelperStatus.NEEDS_PAIRING)
    private val shell = HelperShell(helper, dispatcher)
    private var inFront = false
    private var prepared = 0
    private var invalidated = 0
    private val helperStarts = ArrayList<Intent>()

    private val apps =
        AppsServiceImpl(
            context = context,
            io = dispatcher,
            prepare = {
                prepared++
                false
            },
            // The snapshots are the helper's, so this fails without it, as TreeSession does.
            invalidateSnapshots = {
                shell.require()
                invalidated++
            },
            seq = MutableStateFlow(0L),
            shell = shell,
            // Only the helper reports the app in front, so without it this runs out.
            awaitForeground = {
                if (!shell.isAvailable) delay(FOREGROUND_WAIT_MS)
                shell.isAvailable
            },
            inFront = { inFront },
        )

    @Test
    fun `fails at once from behind`() =
        runTest(dispatcher) {
            val failure = launchFails()
            assertEquals(503, failure.status)
            assertEquals("helper_unavailable", failure.error)
            assertTrue("from the background" in failure.message.orEmpty(), failure.message)
            assertTrue("Pair wireless debugging" in failure.message.orEmpty(), failure.message)
            assertEquals(0, currentTime)
            assertEquals(emptyList<Intent>(), context.started)
        }

    @Test
    fun `wakes nothing from behind`() =
        runTest(dispatcher) {
            launchFails()
            assertEquals(0, prepared)
        }

    @Test
    fun `starts it itself in front`() =
        runTest(dispatcher) {
            inFront = true
            val result = apps.launch(APP, REQUEST)
            assertTrue(result.ok)
            assertEquals("activity", result.backend)
            assertEquals(
                listOf(COMPONENT),
                context.started.map { it.component?.flattenToShortString() },
            )
            assertEquals(1, prepared)
        }

    @Test
    fun `says it can't follow a start`() =
        runTest(dispatcher) {
            inFront = true
            val result = apps.launch(APP, REQUEST)
            assertTrue("can't see" in result.message.orEmpty(), result.message)
            assertEquals(0, currentTime)
        }

    @Test
    fun `no wait has nothing to say`() =
        runTest(dispatcher) {
            inFront = true
            val result = apps.launch(APP, REQUEST.copy(wait = false))
            assertTrue(result.ok)
            assertNull(result.message)
        }

    @Test
    fun `the helper starts it`() =
        runTest(dispatcher) {
            attachHelper()
            val result = apps.launch(APP, REQUEST)
            assertTrue(result.ok)
            assertEquals("shell", result.backend)
            assertEquals(
                listOf(COMPONENT),
                helperStarts.map { it.component?.flattenToShortString() },
            )
            assertEquals(emptyList<Intent>(), context.started)
            assertEquals(1, invalidated)
        }

    @Test
    fun `the helper wins in front`() =
        runTest(dispatcher) {
            attachHelper()
            inFront = true
            apps.launch(APP, REQUEST)
            assertEquals(1, helperStarts.size)
            assertEquals(emptyList<Intent>(), context.started)
        }

    private suspend fun launchFails(): ApiException =
        try {
            fail("Launched: ${apps.launch(APP, REQUEST)}")
        } catch (e: ApiException) {
            e
        }

    /** Registers a helper that takes every start, as a running one does. */
    private fun attachHelper() {
        lateinit var binder: IBinder
        val proxy =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IHelper::class.java)) {
                _,
                method,
                args ->
                when (method.name) {
                    "protocolVersion" -> Registration.PROTOCOL_VERSION
                    "asBinder" -> binder
                    "startActivity" -> {
                        helperStarts += args[0] as Intent
                        StartResult()
                    }
                    else -> error("A launch does not call ${method.name}")
                }
            } as IHelper
        binder =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IBinder::class.java)) {
                _,
                method,
                _ ->
                when (method.name) {
                    "queryLocalInterface" -> proxy
                    "linkToDeath" -> null
                    "hashCode" -> 1
                    "equals" -> false
                    else -> error("A launch does not call ${method.name}")
                }
            } as IBinder
        check(helper.register(binder))
    }

    private companion object {
        const val APP = "com.example.app"
        const val COMPONENT = "com.example.app/.Main"
        const val FOREGROUND_WAIT_MS = 5_000L
        val REQUEST = LaunchRequest(activity = ".Main")
    }
}
