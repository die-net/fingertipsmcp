package com.fingertipsmcp.browser

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.core.settled
import com.fingertipsmcp.model.ApiException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.serializer

/** What the snapshot's page check returns. */
@Serializable
internal data class PageState(
    val visibility: String? = null,
    val pending: List<String> = emptyList(),
)

/** The snapshot's page check, wrapping page-pending.js ([pending]). */
internal fun stateScript(pending: String): String =
    """
        (function () {
        $pending
        return {visibility: document.visibilityState, pending: pagePending()};
        })()
    """
        .trimIndent()

/**
 * The tab's visibility and signs of content still to load. Only advisory, so a page that cannot run
 * the check reads as unknown rather than failing the snapshot.
 */
internal suspend fun pageState(cdp: CdpSession, frameId: String?, script: String): PageState {
    if (frameId == null) return PageState()
    val result =
        try {
            evaluateInFrame(cdp, frameId, script, awaitPromise = false)
        } catch (_: ApiException) {
            return PageState()
        }
    val value = result.value as? JsonObject ?: return PageState()
    return try {
        // serializer<T>(): detekt can't see the serializer() the plugin generates for this module.
        ApiJson.decodeFromJsonElement(serializer<PageState>(), value)
    } catch (_: SerializationException) {
        PageState()
    }
}

/** Whether Chrome reports this tab visible, which only means drawn while its app is in front. */
internal suspend fun tabShowing(cdp: CdpSession): Boolean {
    val state = evaluateIn(cdp, "document.visibilityState", awaitPromise = false).value
    return (state as? JsonPrimitive)?.contentOrNull == "visible"
}

/**
 * With Chrome on screen, only the tab it is showing takes touch. The others report hidden. While
 * Chrome itself is hidden every tab reports visible, which the caller checks first.
 */
internal suspend fun requireShowing(cdp: CdpSession) {
    if (tabShowing(cdp)) return
    throw ApiException(
        409,
        "not_visible",
        "This tab is not the one Chrome is showing, and Chrome only takes cdp touch input " +
            "there. Use input touch, which brings the tab forward, or input cdp-mouse for a tap.",
    )
}

/**
 * Selects the tab, then waits for its text to hold still. Chrome coming forward can select another
 * tab after this one, so the tab has to still be showing at the end, or this tries again, up to
 * [attempts] times. True when it held.
 */
internal suspend fun bringForward(cdp: CdpSession, attempts: Int): Boolean {
    repeat(attempts) {
        cdp.call("Page.bringToFront")
        if (settled(FRONT_MS) { tabShowing(cdp) }) {
            awaitTextStill(cdp)
            if (tabShowing(cdp)) return true
        }
    }
    return false
}

/** Waits until the page's text length holds still for a few ticks, or for the cap. */
internal suspend fun awaitTextStill(cdp: CdpSession) {
    evaluateIn(cdp, GROWTH_SCRIPT, awaitPromise = true)
}

/** How long Chrome may take to select a tab. */
internal const val FRONT_MS = 2_000L
private const val GROWTH_TICK_MS = 250
private const val GROWTH_QUIET_TICKS = 3
private const val GROWTH_MAX_MS = 5_000

private val GROWTH_SCRIPT =
    """
        new Promise((resolve) => {
          const start = Date.now();
          let last = -1;
          let quiet = 0;
          (function tick() {
            const chars = document.body ? document.body.innerText.length : 0;
            quiet = chars === last ? quiet + 1 : 0;
            last = chars;
            if (quiet >= $GROWTH_QUIET_TICKS || Date.now() - start >= $GROWTH_MAX_MS) {
              resolve(chars);
            } else {
              setTimeout(tick, $GROWTH_TICK_MS);
            }
          })();
        })
    """
        .trimIndent()
