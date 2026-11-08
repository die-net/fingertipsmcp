package com.fingertipsmcp.helper.compat

import android.util.Log
import java.lang.reflect.Method

/**
 * Lets this process call hidden framework APIs. `app_process` loading an app APK is subject to the
 * hidden-API blacklist of that APK's targetSdk, which blocks `ServiceManager` and `IInputManager`.
 * Asking for the exemption through reflection on [Class.getDeclaredMethod] is the usual way past
 * that check: the VM attributes the lookup to the reflection caller, not to us.
 */
internal object HiddenApi {
    private const val TAG = "FingertipsHelper"

    fun exempt() {
        try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val classArray = emptyArray<Class<*>>().javaClass
            val getDeclaredMethod =
                Class::class
                    .java
                    .getDeclaredMethod("getDeclaredMethod", String::class.java, classArray)
            val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(vmRuntime, "getRuntime", null) as Method
            // `as Any` keeps the arrays from being spread into Method.invoke's varargs.
            val stringArray = emptyArray<String>().javaClass
            val setExemptions =
                getDeclaredMethod.invoke(
                    vmRuntime,
                    "setHiddenApiExemptions",
                    arrayOf(stringArray) as Any,
                ) as Method
            val runtime = getRuntime.invoke(null)
            setExemptions.invoke(runtime, arrayOf("L") as Any)
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Could not exempt hidden APIs", e)
        }
    }
}
