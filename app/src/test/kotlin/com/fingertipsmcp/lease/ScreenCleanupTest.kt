package com.fingertipsmcp.lease

import com.fingertipsmcp.core.ClipImage
import com.fingertipsmcp.core.ClipboardService
import com.fingertipsmcp.core.InputService
import com.fingertipsmcp.core.PowerService
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.ClipboardContent
import com.fingertipsmcp.model.ClipboardReadRequest
import com.fingertipsmcp.model.ClipboardWriteRequest
import com.fingertipsmcp.model.GestureRequest
import com.fingertipsmcp.model.ImeShowRequest
import com.fingertipsmcp.model.KeyRequest
import com.fingertipsmcp.model.OrientationRequest
import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.SwipeRequest
import com.fingertipsmcp.model.TapRequest
import com.fingertipsmcp.model.TextRequest
import com.fingertipsmcp.model.UnlockRequest
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenCleanupTest {
    private val steps = mutableListOf<String>()
    private var clearClip: () -> ActionResult = { ActionResult(ok = true) }
    /** Whether the home screen comes forward after HOME. */
    private var homeCame = true

    private val cleanup =
        ScreenCleanup(
            Input(),
            Power(),
            Clipboard(),
            showPlaceholder = { steps += "placeholder" },
            awaitHome = {
                steps += "home"
                homeCame
            },
        )

    @Test
    fun `the clipboard is cleared last`() = runTest {
        assertEquals(emptyList<String>(), cleanup.run(lock = true))
        assertEquals(listOf("hideIme", "placeholder", "HOME", "home", "lock", "clearOwn"), steps)
    }

    @Test
    fun `a hand-off skips the lock`() = runTest {
        assertEquals(emptyList<String>(), cleanup.run(lock = false))
        assertEquals(listOf("hideIme", "placeholder", "HOME", "home", "clearOwn"), steps)
    }

    @Test
    fun `a missing home is reported`() = runTest {
        homeCame = false
        val failed = cleanup.run(lock = false)
        assertEquals(listOf("go home: the home screen did not come forward"), failed)
        assertEquals("clearOwn", steps.last())
    }

    @Test
    fun `a failed clear is reported`() = runTest {
        clearClip = { throw ApiException.helperUnavailable("no helper") }
        val failed = cleanup.run(lock = true)
        assertEquals(1, failed.size)
        assertTrue(failed.single().startsWith("clear the clipboard:"), failed.single())
        assertEquals("lock", steps[steps.size - 2])
    }

    @Test
    fun `ready clears any clip`() = runTest {
        assertEquals(emptyList<String>(), cleanup.ready())
        assertEquals(listOf("clear"), steps)
    }

    @Test
    fun `a failed ready is reported`() = runTest {
        clearClip = { throw ApiException.helperUnavailable("no helper") }
        val failed = cleanup.ready()
        assertTrue(failed.single().startsWith("clear the clipboard:"), failed.single())
    }

    private inner class Input : InputService {
        override suspend fun tap(request: TapRequest) = unused()

        override suspend fun swipe(request: SwipeRequest) = unused()

        override suspend fun gesture(request: GestureRequest) = unused()

        override suspend fun pointer(frames: ReceiveChannel<PointerFrame>) = Unit

        override suspend fun key(request: KeyRequest): ActionResult {
            steps += request.key
            return ActionResult(ok = true)
        }

        override suspend fun text(request: TextRequest) = unused()

        override suspend fun hideIme(): ActionResult {
            steps += "hideIme"
            return ActionResult(ok = true)
        }

        override suspend fun showIme(request: ImeShowRequest) = unused()
    }

    private inner class Power : PowerService {
        override suspend fun wake() = unused()

        override suspend fun unlock(request: UnlockRequest) = unused()

        override suspend fun lock(): ActionResult {
            steps += "lock"
            return ActionResult(ok = true)
        }

        override suspend fun orientation(request: OrientationRequest) = unused()

        override suspend fun prepareForAction(autoWake: Boolean) = false
    }

    private inner class Clipboard : ClipboardService {
        override suspend fun read(request: ClipboardReadRequest) = ClipboardContent()

        override suspend fun write(request: ClipboardWriteRequest) = unused()

        override suspend fun image(): ClipImage? = null

        override suspend fun writeImage(bytes: ByteArray, paste: Boolean) = unused()

        override suspend fun clearOwn(): ActionResult {
            steps += "clearOwn"
            return clearClip()
        }

        override suspend fun clear(): ActionResult {
            steps += "clear"
            return clearClip()
        }
    }

    private fun unused(): ActionResult = error("cleanup does not call this")
}
