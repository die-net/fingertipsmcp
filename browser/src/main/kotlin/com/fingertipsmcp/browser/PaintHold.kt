package com.fingertipsmcp.browser

import com.fingertipsmcp.core.settled
import com.fingertipsmcp.model.ApiException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sends [press], the first half of a click or key, past Chrome's hold on a new document's first
 * paint. While it holds that paint the renderer drops input and DevTools still answers `ok`, and
 * only a main frame ends the hold, so on a document Chrome has not drawn it never ends by itself.
 * That is any tab Chrome has not shown since it last loaded, even one shown before that, so the
 * page is asked rather than a record of what FingertipsMCP showed.
 *
 * A document with a paint is past the hold, and nothing else says so: user activation can be there
 * with the hold still on. So on any other document each press has to show that it landed, by an
 * isolated-world listener for [event] hearing it in one of the page's frames, or by user activation
 * appearing, which also comes up from a frame in another process. If the press did not land, a
 * screenshot request ends the hold. Nothing says it has ended, and it can end just after the next
 * press, so [press] repeats until one lands. A press Chrome dropped did nothing, so a page sees
 * only the one that landed. docs/device-notes.md has the measurements.
 *
 * Returns false when the press never landed, for the caller to finish the click or key and then
 * throw [inputDropped].
 */
internal suspend fun pressPastHold(
    cdp: CdpSession,
    event: String,
    press: suspend () -> Unit,
): Boolean {
    val page = frameTree(cdp)
    val top = isolatedWorld(cdp, frameId(page))
    if (!pageSays(cdp, top, "$NO_PAINT && ${listener(event)}")) {
        press()
        return true
    }
    val worlds = listOf(top) + listeningFrames(cdp, page, event)
    val landed: suspend () -> Boolean = {
        press()
        worlds.any { heard(cdp, it) }
    }
    if (landed()) return true
    cdp.post(
        "Page.captureScreenshot",
        buildJsonObject {
            put("format", "jpeg")
            put("quality", 0)
        },
    )
    return settled(HOLD_MS) { landed() }
}

/** The failure for input that Chrome drops, whatever it answered. */
internal fun inputDropped(): ApiException =
    ApiException(
        409,
        "not_visible",
        "Chrome has not drawn this page, so it dropped the input. Use input touch, which brings " +
            "the tab forward.",
    )

/** Worlds listening for [event] in the frames under [node]. A frame Chrome refuses has none. */
private suspend fun listeningFrames(cdp: CdpSession, node: FrameNode, event: String): List<Int> =
    node.childFrames.flatMap { child ->
        val world =
            try {
                isolatedWorld(cdp, frameId(child)).also { pageSays(cdp, it, listener(event)) }
            } catch (e: ApiException) {
                if (e.rejected == null) throw e
                null
            }
        listOfNotNull(world) + listeningFrames(cdp, child, event)
    }

/** Whether the listener in [world] heard the press. A frame that has gone did not. */
private suspend fun heard(cdp: CdpSession, world: Int): Boolean =
    try {
        pageSays(cdp, world, LANDED)
    } catch (e: ApiException) {
        if (e.rejected == null) throw e
        false
    }

private suspend fun pageSays(cdp: CdpSession, world: Int, expression: String): Boolean {
    val value = evaluateInWorld(cdp, world, expression, awaitPromise = false).value
    return (value as? JsonPrimitive)?.booleanOrNull == true
}

/** Starts listening for one [event] in a frame, for [LANDED] to read. Answers true. */
private fun listener(event: String): String =
    """
        (function () {
          const activation = navigator.userActivation;
          const before = !activation || activation.hasBeenActive;
          let seen = false;
          addEventListener('$event', () => { seen = true; }, {capture: true, once: true});
          self.pressLanded = () => seen || (!before && activation.hasBeenActive);
          return true;
        })()
    """
        .trimIndent()

private const val NO_PAINT = "performance.getEntriesByType('paint').length === 0"

/** Whether the press [listener] waits for has come. */
private const val LANDED = "self.pressLanded()"

/** How long Chrome may take to end the hold. It took under 60 ms when measured. */
private const val HOLD_MS = 1_000L
