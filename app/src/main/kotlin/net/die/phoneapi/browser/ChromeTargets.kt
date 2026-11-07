package net.die.phoneapi.browser

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserTarget

/**
 * Chrome's tabs from `Target.getTargets`, which, unlike `/json/list`, uses the same ids as
 * `Target.createTarget` and reports which tab opened a popup. Tabs Chrome has unloaded can be
 * missing.
 */
internal fun chromeTargets(
    result: JsonObject,
    socket: String,
    pid: Int?,
    packageName: String?,
): List<BrowserTarget> =
    chromePages(result).map {
        BrowserTarget(
            id = browserTargetId(socket, it.targetId),
            type = it.type,
            title = it.title?.takeIf { title -> title.isNotBlank() },
            url = it.url?.takeIf { url -> url.isNotBlank() },
            socket = socket,
            pid = pid,
            packageName = packageName,
            openerId = it.openerId?.let { opener -> browserTargetId(socket, opener) },
        )
    }

/**
 * Each of Chrome's tabs, by its Chrome target id, with the browser context it belongs to. Normal
 * tabs share one context and incognito tabs another.
 */
internal fun chromeContexts(result: JsonObject): Map<String, String?> =
    chromePages(result).associate { it.targetId to it.browserContextId }

/** The tabs in a `Target.getTargets` reply. */
internal fun chromePages(result: JsonObject): List<ChromePage> =
    result.decodeCdp<TargetInfos>()?.targetInfos.orEmpty().filter { it.type == "page" }

@Serializable private data class TargetInfos(val targetInfos: List<ChromePage> = emptyList())

/** One target in a `Target.getTargets` reply. */
@Serializable
internal data class ChromePage(
    val targetId: String,
    val type: String = "page",
    val title: String? = null,
    val url: String? = null,
    val openerId: String? = null,
    val browserContextId: String? = null,
)

/**
 * A WebView's pages from `/json/list` on [devtools], which this closes. Gives up after [timeoutMs].
 */
internal suspend fun listJsonTargets(
    devtools: DevtoolsSocket,
    socket: String,
    pid: Int?,
    packageName: String?,
    io: CoroutineContext,
    timeoutMs: Long,
): List<BrowserTarget> = coroutineScope {
    val timedOut = AtomicBoolean(false)
    val closer = launch {
        delay(timeoutMs)
        timedOut.set(true)
        devtools.close()
    }
    try {
        withContext(io) {
            try {
                listedTargets(
                    httpGet(devtools.input, devtools.output, "/json/list", MAX_BODY),
                    socket,
                    pid,
                    packageName,
                )
            } catch (e: SocketTimeoutException) {
                throw ApiException(
                    503,
                    "cdp_timeout",
                    e.message ?: "DevTools did not answer in time",
                    cause = e,
                )
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                if (timedOut.get()) {
                    throw ApiException(
                        503,
                        "cdp_timeout",
                        "DevTools did not answer in time",
                        cause = e,
                    )
                }
                throw ApiException(
                    502,
                    "cdp_error",
                    e.message?.takeIf { it.isNotBlank() } ?: DEVTOOLS_CLOSED,
                    cause = e,
                )
            }
        }
    } finally {
        closer.cancel()
        devtools.close()
    }
}

/**
 * A WebView's pages from `/json/list`. Chrome's come from [chromeTargets] instead, since its
 * `/json/list` ids differ from the ones the `Target` domain uses.
 */
internal fun listedTargets(
    json: String,
    socket: String,
    pid: Int?,
    packageName: String?,
): List<BrowserTarget> {
    val listed =
        try {
            ApiJson.decodeFromString(serializer<List<ListedTarget>>(), json)
        } catch (e: SerializationException) {
            throw ApiException(502, "cdp_error", "DevTools target list was not JSON", cause = e)
        }
    return listed
        .filter { it.type !in SKIPPED_TYPES }
        .map { item ->
            BrowserTarget(
                id = browserTargetId(socket, item.id),
                type = item.type,
                title = item.title?.takeIf { it.isNotBlank() },
                url = item.url?.takeIf { it.isNotBlank() },
                socket = socket,
                pid = pid,
                packageName = packageName,
            )
        }
}

@Serializable
private data class ListedTarget(
    val id: String,
    val type: String = "page",
    val title: String? = null,
    val url: String? = null,
)

private val SKIPPED_TYPES = setOf("service_worker", "shared_worker", "worker")
private const val MAX_BODY = 4 * 1024 * 1024
