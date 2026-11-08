package com.fingertipsmcp.stream

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Copies mirrored frames onto the encoder's input surface, and can draw the last one again.
 *
 * The mirror only draws when the screen changes, so on a still screen nothing reaches the encoder.
 * A sync-frame request then waits forever, and a viewer cannot tell a still screen from a dead one.
 * The relay redraws the last frame when asked, so a joining viewer gets its key frame, and on the
 * schedule in [RedrawPlan]: a few times as the screen settles, so the encoder can sharpen it, then
 * every few seconds. The relay does this itself rather than through the encoder's
 * `KEY_REPEAT_PREVIOUS_FRAME_AFTER`, which some encoders ignore and others honor ten times a
 * second.
 *
 * Until the mirror delivers its first frame there is nothing to redraw.
 *
 * When reading a mirrored frame or presenting one fails, the relay stops drawing and calls
 * [onFailure] once, on its own thread, with the reason. Redrawing the last good frame after that
 * would look like a still screen to the viewer, whose stall check then never fires.
 */
internal class FrameRelay(
    encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val onFailure: (String) -> Unit,
) {
    private val thread = HandlerThread("fingertips-relay").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var texMatrix = 0
    private var position = 0
    private var texCoord = 0
    private var frames: SurfaceTexture? = null
    private var hasFrame = false
    private var released = false
    private var lastPtsNs = 0L
    private val plan = RedrawPlan()
    private val matrix = FloatArray(16)
    private val quad: FloatBuffer =
        ByteBuffer.allocateDirect(QUAD.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD)
                position(0)
            }

    /** The surface the mirror draws into. */
    val input: Surface

    /** Tags the scheduled redraw, so a new schedule can replace it. */
    private val tickToken = Any()

    init {
        input =
            try {
                onGl { setUp(encoderSurface) }
            } catch (e: IllegalStateException) {
                onGlQuietly { tearDown() }
                thread.quitSafely()
                throw e
            }
    }

    /** Draws the last mirrored frame again, so a pending sync-frame request has input. */
    fun redraw() {
        handler.post {
            if (!released && hasFrame) {
                draw()
                schedule()
            }
        }
    }

    /** Stops drawing and frees the GL objects. Call after the mirror stops, before the codec. */
    fun release() {
        onGlQuietly { tearDown() }
        thread.quitSafely()
    }

    private fun setUp(encoderSurface: Surface): Surface {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val config = chooseConfig()
        context =
            EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface =
            EGL14.eglCreateWindowSurface(
                display,
                config,
                encoderSurface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            "eglMakeCurrent failed"
        }
        program = buildProgram()
        texMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        position = GLES20.glGetAttribLocation(program, "aPosition")
        texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        texture = externalTexture()
        val mirrored = SurfaceTexture(texture)
        mirrored.setDefaultBufferSize(width, height)
        mirrored.setOnFrameAvailableListener({ onMirrored() }, handler)
        frames = mirrored
        return Surface(mirrored)
    }

    private fun chooseConfig(): EGLConfig {
        val attributes =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID,
                1,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val chosen = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
        check(chosen && count[0] > 0) { "No recordable EGL config" }
        return checkNotNull(configs[0]) { "No recordable EGL config" }
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val built = GLES20.glCreateProgram()
        GLES20.glAttachShader(built, vertex)
        GLES20.glAttachShader(built, fragment)
        GLES20.glLinkProgram(built)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(built, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(built)
            GLES20.glDeleteProgram(built)
            error("Relay program did not link: $log")
        }
        return built
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Relay shader did not compile: $log")
        }
        return shader
    }

    private fun externalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    // updateTexImage throws a bare RuntimeException for some errors, and an uncaught one here
    // would take down the app process.
    @Suppress("TooGenericExceptionCaught")
    private fun onMirrored() {
        val mirrored = frames ?: return
        if (released) return
        try {
            mirrored.updateTexImage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateTexImage", e)
            fail("The relay could not read a mirrored frame")
            return
        }
        mirrored.getTransformMatrix(matrix)
        hasFrame = true
        plan.mirrored(SystemClock.uptimeMillis())
        draw()
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacksAndMessages(tickToken)
        plan.delayMs(SystemClock.uptimeMillis())?.let {
            handler.postDelayed(::onTick, tickToken, it)
        }
    }

    private fun onTick() {
        if (released) return
        if (plan.due(SystemClock.uptimeMillis())) draw()
        schedule()
    }

    private fun draw() {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(texMatrix, 1, false, matrix, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, STRIDE, quad)
        GLES20.glEnableVertexAttribArray(position)
        quad.position(2)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, STRIDE, quad)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // The encoder needs rising timestamps, and a redraw has no capture time of its own.
        val pts = maxOf(System.nanoTime(), lastPtsNs + 1)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, pts)
        if (!EGL14.eglSwapBuffers(display, eglSurface)) {
            Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            fail("The relay could not hand a frame to the encoder")
            return
        }
        lastPtsNs = pts
        plan.drew(SystemClock.uptimeMillis())
    }

    /** Stops drawing, as [release] would, and reports [reason] once. */
    private fun fail(reason: String) {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(tickToken)
        onFailure(reason)
    }

    private fun tearDown() {
        released = true
        handler.removeCallbacksAndMessages(tickToken)
        frames?.let {
            it.setOnFrameAvailableListener(null)
            it.release()
        }
        frames = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        program = 0
        texture = 0
        eglSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    /** Runs [block] on the GL thread and waits, rethrowing its failure as IllegalStateException. */
    private fun <T> onGl(block: () -> T): T {
        val task = FutureTask(block)
        check(handler.post(task)) { "The relay thread has stopped" }
        return try {
            task.get(GL_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw IllegalStateException(e.cause?.message ?: "The relay failed", e)
        } catch (e: TimeoutException) {
            throw IllegalStateException("The relay thread did not answer", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted waiting for the relay thread", e)
        }
    }

    private fun onGlQuietly(block: () -> Unit) {
        try {
            onGl(block)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "relay release", e)
        }
    }

    private companion object {
        const val TAG = "FingertipsStream"
        const val GL_WAIT_MS = 2_000L
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val STRIDE = 4 * Float.SIZE_BYTES

        /** x, y, s, t for a full-screen triangle strip. */
        val QUAD = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)

        const val VERTEX_SHADER =
            """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
            """

        const val FRAGMENT_SHADER =
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
            """
    }
}
