package android.util

/**
 * Stands in for the platform `Log` in JVM unit tests. The test classpath puts this ahead of the
 * Android stub jar, whose methods throw, so production code can log without guarding each call.
 */
// A stub that drops every line, with the platform's method names.
@Suppress("FunctionOnlyReturningConstant", "UnusedParameter", "FunctionNameMinLength")
object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    /** Nothing is loggable, so slf4j's logcat bridge drops every line, as the methods below do. */
    @JvmStatic fun isLoggable(tag: String?, level: Int): Boolean = false

    @JvmStatic fun println(priority: Int, tag: String?, msg: String): Int = 0

    @JvmStatic fun getStackTraceString(tr: Throwable?): String = ""

    @JvmStatic fun d(tag: String?, msg: String): Int = 0

    @JvmStatic fun i(tag: String?, msg: String): Int = 0

    @JvmStatic fun w(tag: String?, msg: String): Int = 0

    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = 0

    @JvmStatic fun e(tag: String?, msg: String): Int = 0

    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = 0
}
