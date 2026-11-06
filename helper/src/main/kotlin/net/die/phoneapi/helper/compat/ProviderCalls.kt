package net.die.phoneapi.helper.compat

import android.content.AttributionSource
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import java.lang.reflect.Method

/**
 * `ContentResolver.call` from `app_process` is rejected: the process is not a registered app, and
 * the system context's package (`android`) does not match the shell uid. `adb shell content` uses
 * `IActivityManager.getContentProviderExternal`, which is the path that allows uid 2000.
 */
internal object ProviderCalls {
    private const val SHELL = "com.android.shell"
    private const val PER_USER_RANGE = 100_000
    private const val MODERN_CALL_ARITY = 5
    private const val TAGGED_CALL_ARITY = 6

    /** Calls [method] on the provider at [authority]. Every failure is a runtime exception. */
    fun call(authority: String, method: String, extras: Bundle): Bundle? =
        reflective("Content provider call") {
            val activityManager = activityManager()
            val token = Binder()
            val get = externalGet(activityManager)
            val holder =
                invoke(get, activityManager, fill(get, authority, token)) ?: return@reflective null
            try {
                invokeCall(provider(holder), authority, method, extras)
            } finally {
                val remove = externalRemove(activityManager)
                invoke(remove, activityManager, fill(remove, authority, token))
            }
        }

    private fun activityManager(): Any {
        val manager = Class.forName("android.app.ActivityManager")
        return checkNotNull(manager.getDeclaredMethod("getService").invoke(null)) {
            "ActivityManager is unavailable"
        }
    }

    private fun externalGet(activityManager: Any): Method =
        activityManager.javaClass.methods
            .filter { it.name == "getContentProviderExternal" }
            .maxBy { it.parameterTypes.size }

    private fun externalRemove(activityManager: Any): Method =
        activityManager.javaClass.methods.firstOrNull {
            it.name == "removeContentProviderExternalAsUser"
        } ?: activityManager.javaClass.methods.first { it.name == "removeContentProviderExternal" }

    /** Fills `name`, user id, token, and an optional tag, in whatever order the overload uses. */
    private fun fill(method: Method, authority: String, token: IBinder): Array<Any?> {
        var strings = 0
        return Array(method.parameterTypes.size) { index ->
            when (method.parameterTypes[index].name) {
                "java.lang.String" -> if (strings++ == 0) authority else "phoneapi"
                "int" -> Process.myUid() / PER_USER_RANGE
                "android.os.IBinder" -> token
                else -> null
            }
        }
    }

    private fun provider(holder: Any): Any {
        val field = holder.javaClass.getDeclaredField("provider")
        field.isAccessible = true
        return checkNotNull(field.get(holder)) { "Content provider is not published" }
    }

    private fun invokeCall(
        provider: Any,
        authority: String,
        method: String,
        extras: Bundle,
    ): Bundle? {
        val calls = provider.javaClass.methods.filter { it.name == "call" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val modern = calls.first { call ->
                call.parameterTypes.size == MODERN_CALL_ARITY &&
                    call.parameterTypes[0].name == "android.content.AttributionSource"
            }
            val source = AttributionSource.Builder(Process.myUid()).setPackageName(SHELL).build()
            return invoke(modern, provider, arrayOf(source, authority, method, null, extras))
                as? Bundle
        }
        // Android 10: (callingPkg, authority, method, arg, extras). Android 11 adds a feature id
        // after callingPkg.
        val legacy = calls.maxBy { it.parameterTypes.size }
        val args =
            if (legacy.parameterTypes.size >= TAGGED_CALL_ARITY) {
                arrayOf(SHELL, null, authority, method, null, extras)
            } else {
                arrayOf<Any?>(SHELL, authority, method, null, extras)
            }
        return invoke(legacy, provider, args) as? Bundle
    }

    @Suppress("SpreadOperator") // The argument list is the call itself, a few values.
    private fun invoke(method: Method, target: Any?, args: Array<Any?>): Any? =
        method.invoke(target, *args)
}
