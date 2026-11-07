package net.die.phoneapi.helper

/** Strings for the content-provider handshake. The app's provider accepts the same names. */
object Registration {
    /**
     * Bumped when IHelper reply parcels or tree ops change. The app rejects a helper that reports
     * anything else and asks that process to exit.
     */
    const val PROTOCOL_VERSION: Int = 9
    const val METHOD: String = "register"
    const val EXTRA_BINDER: String = "binder"
    const val EXTRA_APP_UID: String = "appUid"

    /** Binder that dies with the app process, so the helper can see that registration is gone. */
    const val EXTRA_APP_BINDER: String = "appBinder"
    const val AUTHORITY_SUFFIX: String = ".helper"
}
