package com.fingertipsmcp.helper

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.fingertipsmcp.helper.compat.ProviderCalls

/**
 * Registers [helper] with the app until the installed APK disappears.
 *
 * The USB and wireless `app_process` entry points use this, so a package update makes the old
 * process exit and the new app can start a fresh one. After a registration is accepted, it is
 * refreshed only when the app's binder dies.
 */
internal object HelperDaemon {
    private const val TAG = "FingertipsHelper"
    private const val RETRY_MS = 5_000L

    @Volatile private var daemonLooper: Looper? = null

    /** The looper [serve] is pumping. UiAutomation delivers events here. */
    fun looper(): Looper = daemonLooper ?: error("The helper looper is not prepared")

    /**
     * Runs on the process's main thread and pumps its looper as the main looper, since nothing else
     * in `app_process` prepares one. Framework code expects `Looper.getMainLooper()`: Android 16's
     * `AccessibilityInteractionClient` builds a Handler on it when UiAutomation connects, and
     * crashes the helper when it is null.
     */
    @Suppress("DEPRECATION") // prepareMainLooper is meant for the system, which this stands in for.
    fun serve(helper: HelperImpl, packageName: String?, classpath: String) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        if (Looper.myLooper() == null) Looper.prepare()
        val looper = Looper.myLooper() ?: error("Looper was not prepared")
        daemonLooper = looper
        val handler = Handler(looper)
        val registration = RegistrationLink(helper, packageName, handler)
        val tick =
            object : Runnable {
                override fun run() {
                    if (!helper.apkStillThere(classpath)) {
                        Log.i(TAG, "APK is gone; exiting so the updated app can relaunch us")
                        halt(0)
                    }
                    registration.ensure()
                    handler.postDelayed(this, RETRY_MS)
                }
            }
        tick.run()
        Log.i(
            TAG,
            "helper running pid=${Process.myPid()} protocol=${Registration.PROTOCOL_VERSION} " +
                "pkg=${packageName ?: "unknown"}",
        )
        Looper.loop()
    }

    @Suppress("ExitOutsideMain") // This process is the helper, so exit codes are how it reports.
    private fun halt(code: Int): Nothing {
        System.exit(code)
        error("unreachable")
    }
}

private class RegistrationLink(
    private val helper: HelperImpl,
    private val packageName: String?,
    private val handler: Handler,
) {
    private val lock = Any()
    private var app: IBinder? = null
    private val death = IBinder.DeathRecipient { onAppDied() }

    private fun onAppDied() {
        synchronized(lock) { app = null }
        Log.i(TAG, "App died; registering again")
        handler.post { ensure() }
    }

    fun ensure() {
        if (packageName == null) return
        val current = synchronized(lock) { app }
        if (current != null && current.isBinderAlive) return
        val bound = register() ?: return
        synchronized(lock) {
            val raced = app
            if (raced != null && raced.isBinderAlive) return
            try {
                bound.linkToDeath(death, 0)
            } catch (e: RemoteException) {
                Log.w(TAG, "App binder already dead", e)
                return
            }
            app = bound
        }
    }

    @Suppress("TooGenericExceptionCaught") // The app may be down; the next check retries.
    private fun register(): IBinder? {
        val name = packageName ?: return null
        return try {
            val extras = Bundle().apply { putBinder(Registration.EXTRA_BINDER, helper) }
            val authority = name + Registration.AUTHORITY_SUFFIX
            val result =
                ProviderCalls.call(authority, Registration.METHOD, extras)
                    ?: run {
                        Log.w(TAG, "App did not accept registration")
                        return null
                    }
            val uid = result.getInt(Registration.EXTRA_APP_UID, -1)
            if (uid <= 0) {
                Log.w(TAG, "App did not accept registration")
                return null
            }
            val appBinder = result.getBinder(Registration.EXTRA_APP_BINDER)
            helper.adoptRegisteredUid(uid)
            if (appBinder == null) {
                Log.w(TAG, "App did not return a binder")
                return null
            }
            Log.i(TAG, "Registered with the app")
            appBinder
        } catch (e: RuntimeException) {
            // The app process may not be up yet, or may reject the caller. The next check retries.
            Log.w(TAG, "Registration failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            null
        }
    }

    private companion object {
        const val TAG = "FingertipsHelper"
    }
}
