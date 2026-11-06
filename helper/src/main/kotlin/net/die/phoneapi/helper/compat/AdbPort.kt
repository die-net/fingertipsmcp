package net.die.phoneapi.helper.compat

import android.os.IBinder
import android.util.Log

/**
 * The port wireless debugging listens on, from the adb service, which is where Settings reads it.
 * Nothing else gives it reliably: SELinux keeps `service.adb.tls.port` from apps and the shell, and
 * adbd's mDNS record can end while adbd keeps listening. `getAdbWirelessPort` needs
 * `MANAGE_DEBUGGING`, which the shell user holds.
 */
internal object AdbPort {
    private const val TAG = "PhoneApiHelper"
    private const val UNAVAILABLE = -1

    /** The port, or 0 or less while wireless debugging is off or the service can't say. */
    fun read(): Int {
        val binder = serviceBinder("adb")
        if (binder == null) {
            Log.w(TAG, "The adb service is not available to this process")
            return UNAVAILABLE
        }
        return try {
            val stub = Class.forName("android.debug.IAdbManager\$Stub")
            val manager = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            manager.javaClass.getMethod("getAdbWirelessPort").invoke(manager) as Int
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Could not read the wireless debugging port", e)
            UNAVAILABLE
        }
    }
}
