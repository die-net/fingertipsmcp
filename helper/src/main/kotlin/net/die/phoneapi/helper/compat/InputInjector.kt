package net.die.phoneapi.helper.compat

import android.os.IBinder
import android.util.Log
import android.view.InputEvent
import java.lang.reflect.Method

/**
 * `InputManager.injectInputEvent` through the input service's Binder. The method gained optional
 * trailing arguments on newer releases, so the shortest matching overload is the one that just
 * injects.
 */
internal class InputInjector {
    private val manager: Any
    private val inject: Method

    init {
        val binder =
            serviceBinder("input") ?: error("The input service is not available to this process")
        manager =
            reflective("IInputManager") {
                val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterface = stub.getMethod("asInterface", IBinder::class.java)
                checkNotNull(asInterface.invoke(null, binder)) { "IInputManager is null" }
            }
        inject =
            manager.javaClass.methods
                .filter { method ->
                    method.name == "injectInputEvent" &&
                        method.parameterTypes.size >= MIN_ARITY &&
                        method.parameterTypes[0].isAssignableFrom(InputEvent::class.java)
                }
                .minByOrNull { it.parameterTypes.size }
                ?: error("IInputManager has no injectInputEvent method")
        Log.i(TAG, "injectInputEvent ${inject.parameterTypes.joinToString { it.simpleName }}")
    }

    fun inject(event: InputEvent, mode: Int): Boolean {
        val params = inject.parameterTypes
        val args = arrayOfNulls<Any>(params.size)
        args[0] = event
        args[1] = mode
        for (i in MIN_ARITY until params.size) {
            args[i] = if (params[i] == Int::class.javaPrimitiveType) 0 else null
        }
        return reflective("injectInputEvent") {
            @Suppress("SpreadOperator") // A handful of trailing zeros; the copy is the call itself.
            inject.invoke(manager, *args) as Boolean
        }
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val MIN_ARITY = 2
    }
}

internal fun serviceBinder(name: String): IBinder? =
    reflective("ServiceManager.getService") {
        val manager = Class.forName("android.os.ServiceManager")
        val getService = manager.getMethod("getService", String::class.java)
        getService.invoke(null, name) as? IBinder
    }
