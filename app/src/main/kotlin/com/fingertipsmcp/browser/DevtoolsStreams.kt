package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

/**
 * Runs [block] on this socket's streams and turns a socket failure into an [ApiException]. A close
 * by the read watchdog is a `cdp_timeout`, not DevTools hanging up.
 */
internal suspend fun <T> DevtoolsSocket.withStreams(
    block: suspend (InputStream, OutputStream) -> T
): T =
    try {
        block(input, output)
    } catch (e: ApiException) {
        throw if (timedOut && e.error == "cdp_error") devtoolsTimeout(e) else e
    } catch (e: SocketTimeoutException) {
        throw devtoolsTimeout(e, e.message ?: NO_ANSWER)
    } catch (e: IOException) {
        throw if (timedOut) devtoolsTimeout(e) else devtoolsClosed(e)
    }

private fun devtoolsTimeout(cause: Throwable, message: String = NO_ANSWER): ApiException =
    ApiException(503, "cdp_timeout", message, cause = cause)

private fun devtoolsClosed(cause: IOException): ApiException =
    ApiException(
        502,
        "cdp_error",
        cause.message?.takeIf { it.isNotBlank() } ?: DEVTOOLS_CLOSED,
        cause = cause,
    )

private const val NO_ANSWER = "DevTools did not answer in time"
