package com.fingertipsmcp.helper.compat

import android.graphics.Rect
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.fingertipsmcp.helper.HelperDaemon
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Mirrors a display onto an encoder [Surface]. Prefers the hidden `DisplayManager` method shell can
 * call, and falls back to `SurfaceControl.createDisplay` where that method is absent.
 */
internal class MirrorDisplay {
    private val lock = Any()
    private var virtual: VirtualDisplay? = null
    private var token: IBinder? = null

    fun start(surface: Surface, width: Int, height: Int, displayId: Int) {
        synchronized(lock) {
            releaseLocked()
            require(width >= 2 && height >= 2) { "mirror size ${width}x$height is too small" }
            require(width % 2 == 0 && height % 2 == 0) {
                "mirror size ${width}x$height must be even"
            }
            try {
                virtual = createVirtualDisplay(surface, width, height, displayId)
                Log.i(TAG, "Mirroring display $displayId at ${width}x$height via DisplayManager")
            } catch (e: ReflectiveOperationException) {
                Log.w(TAG, "DisplayManager mirror is unavailable", unwrap(e))
                token = surfaceDisplay(surface, width, height, displayId)
                Log.i(TAG, "Mirroring display $displayId at ${width}x$height via SurfaceControl")
            }
        }
    }

    fun stop() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        virtual?.release()
        virtual = null
        token?.let { destroyDisplay(it) }
        token = null
    }

    private fun createVirtualDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        displayId: Int,
    ): VirtualDisplay {
        val manager = Class.forName("android.hardware.display.DisplayManager")
        val method =
            manager.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
            )
        val created =
            invoke(method, null, "fingertips", width, height, displayId, surface) as? VirtualDisplay
        return created ?: error("createVirtualDisplay returned null")
    }

    private fun surfaceDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        displayId: Int,
    ): IBinder =
        try {
            createSurfaceDisplay(surface, width, height, displayId)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException(unwrap(e).message ?: "Could not mirror the display", e)
        }

    private fun createSurfaceDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        displayId: Int,
    ): IBinder {
        val info = displayInfo(displayId)
        val surfaceControl = Class.forName("android.view.SurfaceControl")
        val created =
            invoke(
                surfaceControl.getMethod(
                    "createDisplay",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                ),
                null,
                "fingertips",
                false,
            )
                as? IBinder ?: error("createDisplay returned null")
        val device = Rect(0, 0, info.width, info.height)
        val target = Rect(0, 0, width, height)
        transaction(surfaceControl) {
            invoke(
                surfaceControl.getMethod(
                    "setDisplaySurface",
                    IBinder::class.java,
                    Surface::class.java,
                ),
                null,
                created,
                surface,
            )
            setProjection(surfaceControl, created, device, target)
            invoke(
                surfaceControl.getMethod(
                    "setDisplayLayerStack",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                ),
                null,
                created,
                info.layerStack,
            )
        }
        Handler(HelperDaemon.looper()).postDelayed({ nudge(created, device, target) }, NUDGE_MS)
        return created
    }

    /**
     * Projects [display] two pixels short and back, in two transactions, so SurfaceFlinger draws it
     * again with the same content.
     *
     * Samsung's Android 10 SurfaceFlinger can leave a new `SurfaceControl` display undrawn until
     * the screen changes. On a Galaxy S9+ with a still screen, the first frame took over a second
     * in 9 of 20 starts, and in one it had not come after 8 seconds. With this nudge at [NUDGE_MS],
     * all 20 had a frame within 340 ms. The cause is in SurfaceFlinger, out of reach, so this works
     * around it. On a device that already drew, it costs one or two extra frames.
     */
    private fun nudge(display: IBinder, device: Rect, target: Rect) {
        synchronized(lock) {
            if (token !== display) return
            try {
                val surfaceControl = Class.forName("android.view.SurfaceControl")
                val short = Rect(target.left, target.top, target.right, target.bottom - 2)
                transaction(surfaceControl) {
                    setProjection(surfaceControl, display, device, short)
                }
                transaction(surfaceControl) {
                    setProjection(surfaceControl, display, device, target)
                }
            } catch (e: ReflectiveOperationException) {
                Log.w(TAG, "Could not nudge the mirror", unwrap(e))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not nudge the mirror", e)
            }
        }
    }

    private fun transaction(surfaceControl: Class<*>, block: () -> Unit) {
        invoke(surfaceControl.getMethod("openTransaction"))
        try {
            block()
        } finally {
            invoke(surfaceControl.getMethod("closeTransaction"))
        }
    }

    private fun setProjection(
        surfaceControl: Class<*>,
        display: IBinder,
        device: Rect,
        target: Rect,
    ) {
        invoke(
            surfaceControl.getMethod(
                "setDisplayProjection",
                IBinder::class.java,
                Int::class.javaPrimitiveType,
                Rect::class.java,
                Rect::class.java,
            ),
            null,
            display,
            0,
            device,
            target,
        )
    }

    private fun destroyDisplay(display: IBinder) {
        try {
            val surfaceControl = Class.forName("android.view.SurfaceControl")
            invoke(surfaceControl.getMethod("destroyDisplay", IBinder::class.java), null, display)
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "destroyDisplay failed", unwrap(e))
        }
    }

    private fun displayInfo(displayId: Int): DisplaySize {
        val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = invoke(globalClass.getDeclaredMethod("getInstance"))
        val info =
            invoke(
                globalClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType),
                global,
                displayId,
            ) ?: error("Display $displayId was not found")
        val type = info.javaClass
        return DisplaySize(
            width = type.getField("logicalWidth").getInt(info),
            height = type.getField("logicalHeight").getInt(info),
            layerStack = type.getField("layerStack").getInt(info),
        )
    }

    private fun invoke(
        method: Method,
        target: Any? = null,
        vararg args: Any?,
    ): Any? =
        try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw IllegalStateException(e.targetException.message ?: method.name, e)
        }

    private fun unwrap(error: ReflectiveOperationException): Throwable =
        (error as? InvocationTargetException)?.targetException ?: error

    private data class DisplaySize(val width: Int, val height: Int, val layerStack: Int)

    private companion object {
        const val TAG = "FingertipsHelper"

        /** How long after creating a `SurfaceControl` mirror to [nudge] it. */
        const val NUDGE_MS = 250L
    }
}
