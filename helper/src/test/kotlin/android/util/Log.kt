package android.util

/**
 * Stands in for the platform `Log` in JVM unit tests. The test classpath puts this ahead of the
 * Android stub jar, whose methods throw, so production code can log without guarding each call.
 */
// A stub that drops every line, with the platform's method names.
@Suppress("FunctionOnlyReturningConstant", "UnusedParameter", "FunctionNameMinLength")
object Log {
    @JvmStatic fun w(tag: String?, msg: String): Int = 0

    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = 0
}
