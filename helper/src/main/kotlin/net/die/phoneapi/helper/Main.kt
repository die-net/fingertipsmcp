package net.die.phoneapi.helper

import android.util.Log
import java.io.IOException
import net.die.phoneapi.helper.compat.HiddenApi

/**
 * Entry point for `app_process`, running as the shell UID.
 *
 * The first process only exists to detach from the `adb shell` session: it starts a `setsid` child
 * and exits, so unplugging USB does not deliver SIGHUP to the helper. The child registers its
 * Binder with the app and stays up until the package is replaced.
 */
object Main {
    private const val TAG = "PhoneApiHelper"

    @JvmStatic
    fun main(args: Array<String>) {
        val options = Options.parse(args)
        if (options.daemon) run(options.packageName) else detach(options.packageName)
    }

    private fun detach(packageName: String) {
        val classpath = System.getenv("CLASSPATH")
        if (classpath.isNullOrBlank()) {
            Log.e(TAG, "CLASSPATH is not set; launch with CLASSPATH=\$(pm path <pkg>)")
            halt(1)
        }
        val command =
            "setsid app_process / ${Main::class.java.name} --daemon --pkg $packageName " +
                "</dev/null >/dev/null 2>&1 &"
        try {
            val process =
                ProcessBuilder("sh", "-c", command)
                    .apply { environment()["CLASSPATH"] = classpath }
                    .start()
            // `sh` returns as soon as the child is backgrounded.
            if (process.waitFor() != 0) {
                Log.e(TAG, "detach shell exited ${process.exitValue()}")
                halt(1)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not detach", e)
            halt(1)
        }
        halt(0)
    }

    /** Held for the life of the process; dropping it would let a second helper start. */
    private var instance: AutoCloseable? = null

    private fun run(packageName: String) {
        // The lock's address type is a hidden class, so the exemption comes first.
        HiddenApi.exempt()
        holdInstance(packageName)
        val helper = HelperImpl()
        helper.pinPackage(packageName)
        val classpath = System.getenv("CLASSPATH").orEmpty()
        HelperDaemon.serve(helper, packageName, classpath)
    }

    /** Takes this package's [InstanceLock], or exits when another helper for it holds the lock. */
    @Suppress("MissingUseCall") // Held until the process exits, which releases it.
    private fun holdInstance(packageName: String) {
        try {
            instance =
                InstanceLock().acquire(packageName)
                    ?: run {
                        Log.i(TAG, "A helper for $packageName is already running")
                        halt(0)
                    }
        } catch (e: IOException) {
            runUnlocked(e)
        } catch (e: IllegalStateException) {
            runUnlocked(e)
        }
    }

    /**
     * Without the lock a second launch could start a second helper, which the app stops when it
     * registers. Running is still better than leaving the app with none.
     */
    private fun runUnlocked(why: Exception) {
        Log.w(TAG, "Could not take the helper lock", why)
    }

    @Suppress("ExitOutsideMain") // This process is the helper, so exit codes are how it reports.
    private fun halt(code: Int): Nothing {
        System.exit(code)
        error("unreachable")
    }

    private data class Options(val packageName: String, val daemon: Boolean) {
        companion object {
            private val PACKAGE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")

            fun parse(args: Array<String>): Options {
                var packageName: String? = null
                var daemon = false
                var i = 0
                while (i < args.size) {
                    when (args[i]) {
                        "--daemon" -> daemon = true
                        "--pkg" -> packageName = args.getOrNull(++i)
                        else -> Log.w(TAG, "Ignoring argument ${args[i]}")
                    }
                    i++
                }
                val pkg =
                    packageName?.takeIf { PACKAGE.matches(it) }
                        ?: run {
                            Log.e(TAG, "Pass --pkg <applicationId>")
                            halt(2)
                        }
                return Options(pkg, daemon)
            }
        }
    }
}
