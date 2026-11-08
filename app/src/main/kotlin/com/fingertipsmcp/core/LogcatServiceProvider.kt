package com.fingertipsmcp.core

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider

/**
 * Sends slf4j to logcat, so code shared with deskapi logs the way the app always has. The tag is
 * the logger name after its last dot. FingertipsMCP's own loggers follow `Log.isLoggable`, which
 * allows info and above by default. Libraries such as Ktor and the MCP SDK only reach logcat at
 * warn and above unless their tag is raised with `setprop log.tag.<tag>`.
 */
class LogcatServiceProvider : SLF4JServiceProvider {
    private val loggers = LogcatLoggers()
    private val markers = BasicMarkerFactory()
    private val mdc = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = loggers

    override fun getMarkerFactory(): IMarkerFactory = markers

    override fun getMDCAdapter(): MDCAdapter = mdc

    override fun getRequestedApiVersion(): String = API_VERSION

    override fun initialize() = Unit

    private companion object {
        const val API_VERSION = "2.0.99"
    }
}

private class LogcatLoggers : ILoggerFactory {
    private val loggers = ConcurrentHashMap<String, Logger>()

    override fun getLogger(name: String): Logger = loggers.getOrPut(name) { LogcatLogger(name) }
}

private class LogcatLogger(loggerName: String) : LegacyAbstractLogger() {
    private val tag = loggerName.substringAfterLast('.').take(MAX_TAG).ifEmpty { "slf4j" }
    private val ours = loggerName.startsWith(OWN_PREFIX)

    init {
        name = loggerName
    }

    override fun isTraceEnabled(): Boolean = enabled(Log.VERBOSE)

    override fun isDebugEnabled(): Boolean = enabled(Log.DEBUG)

    override fun isInfoEnabled(): Boolean = enabled(Log.INFO)

    override fun isWarnEnabled(): Boolean = enabled(Log.WARN)

    override fun isErrorEnabled(): Boolean = enabled(Log.ERROR)

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        val message = MessageFormatter.basicArrayFormat(messagePattern, arguments).orEmpty()
        val text =
            if (throwable == null) message else message + "\n" + Log.getStackTraceString(throwable)
        Log.println(priority(level), tag, text)
    }

    /** A library's tag counts only at warn and above, or once raised to allow debug. */
    private fun enabled(priority: Int): Boolean {
        if (!Log.isLoggable(tag, priority)) return false
        return ours || priority >= Log.WARN || Log.isLoggable(tag, Log.DEBUG)
    }

    private fun priority(level: Level): Int =
        when (level) {
            Level.TRACE -> Log.VERBOSE
            Level.DEBUG -> Log.DEBUG
            Level.INFO -> Log.INFO
            Level.WARN -> Log.WARN
            Level.ERROR -> Log.ERROR
        }

    private companion object {
        const val MAX_TAG = 23
        const val OWN_PREFIX = "Fingertips"
    }
}
