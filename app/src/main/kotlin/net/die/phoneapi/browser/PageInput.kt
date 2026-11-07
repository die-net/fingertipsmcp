package net.die.phoneapi.browser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import net.die.phoneapi.core.settled
import net.die.phoneapi.helper.TouchscreenInfo
import net.die.phoneapi.input.FingerMotion
import net.die.phoneapi.input.Humanizer
import net.die.phoneapi.input.SwipeSpec
import net.die.phoneapi.input.fallbackTouchscreen
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.TimedPoint

/**
 * Pointer, key, and text for one page. `touch` is a hardware event after the tab is in front: its
 * app on screen and the tab selected ([showTab]). `cdp` stays on that target: keys and text reach a
 * background tab, but Chrome only takes touch on the tab it is showing, on screen. `cdp-mouse` taps
 * with a mouse click, which reaches a background tab even with Chrome hidden.
 */
internal class PageInput(
    private val touchAt: suspend (Rect, Boolean) -> ActionResult,
    private val swipeAt: suspend (Point, Point, SwipeSpec, Rect) -> ActionResult,
    private val gestureAt: suspend (List<List<TimedPoint>>, Boolean, Rect) -> ActionResult,
    private val pressKey: suspend (KeyRequest) -> ActionResult,
    private val typeText: suspend (TextRequest) -> ActionResult,
    /** Where the app's page is drawn on screen; fails with `content_unavailable` when it is not. */
    private val contentBounds: suspend (String?) -> Rect,
    /** Brings the app up when it is not in front, and fails when it does not come. */
    private val appUp: suspend (String?) -> Unit,
    private val quads: suspend (CdpSession, String, TapTarget) -> JsonArray,
    private val focusNode: suspend (CdpSession, String, TapTarget) -> JsonObject,
    private val target: (String?, String?) -> TapTarget?,
    private val refreshHz: () -> Float = { DEFAULT_REFRESH_HZ },
    private val touchscreen: suspend () -> TouchscreenInfo = { fallbackTouchscreen() },
) {
    private val paths = Humanizer()

    suspend fun tap(
        cdp: CdpSession,
        id: String,
        wanted: TapTarget,
        pkg: String?,
        request: BrowserTapRequest,
    ): ActionResult =
        when (request.input) {
            BrowserInput.AUTO,
            BrowserInput.TOUCH -> touchTap(cdp, id, wanted, pkg, request.humanize)
            BrowserInput.CDP -> cdpTap(cdp, id, wanted, request.humanize)
            BrowserInput.CDP_MOUSE -> mouseTap(cdp, id, wanted, request.humanize)
        }

    fun checkSwipe(request: BrowserSwipeRequest) {
        if (request.input == BrowserInput.CDP_MOUSE) throw mouseOnlyTaps("swipe")
        if (request.durationMs !in MIN_SWIPE_MS..MAX_SWIPE_MS) {
            throw ApiException.badRequest("durationMs must be $MIN_SWIPE_MS..$MAX_SWIPE_MS")
        }
        val from = request.from
        val to = request.to
        if (from != null || to != null) {
            if (from == null || to == null) {
                throw ApiException.badRequest("Pass both from and to, or a direction")
            }
            if (request.direction != null) {
                throw ApiException.badRequest("Pass from and to, or a direction, not both")
            }
        } else if (request.direction == null) {
            throw ApiException.badRequest("Pass from and to, or a direction")
        }
        target(request.ref, request.selector)
    }

    suspend fun swipe(
        cdp: CdpSession,
        id: String,
        pkg: String?,
        request: BrowserSwipeRequest,
    ): ActionResult {
        val spec =
            SwipeSpec(
                request.durationMs,
                request.fling,
                request.humanize,
                sampleRateHz = refreshHz().takeIf { it > 0f } ?: DEFAULT_REFRESH_HZ,
            )
        return when (request.input) {
            BrowserInput.AUTO,
            BrowserInput.TOUCH -> {
                showTab(cdp, pkg)
                val ends = swipeEnds(cdp, id, request)
                val metrics = cdp.call("Page.getLayoutMetrics")
                val content = contentBounds(pkg)
                swipeAt(
                    screenPoint(ends.first.x, ends.first.y, metrics, content),
                    screenPoint(ends.second.x, ends.second.y, metrics, content),
                    spec,
                    content,
                )
            }
            BrowserInput.CDP_MOUSE -> throw mouseOnlyTaps("swipe")
            BrowserInput.CDP -> {
                val metrics = cdp.call("Page.getLayoutMetrics")
                val ends = swipeEnds(cdp, id, request, metrics)
                val path = cdpSwipePath(ends.first, ends.second, spec, metrics, paths)
                val motion = if (spec.fling) FingerMotion.FLING else FingerMotion.DRAG
                dispatchTouchPath(
                    cdp,
                    listOf(path),
                    listOf(paths.finger(path.size, motion, spec.humanize)),
                    axes(),
                    dipScale(metrics),
                )
                cdpPoints(listOf(path))
            }
        }
    }

    fun checkGesture(request: BrowserGestureRequest) {
        if (request.input == BrowserInput.CDP_MOUSE) throw mouseOnlyTaps("gesture")
        val pointers = request.pointers
        if (pointers.isEmpty() || pointers.any { it.isEmpty() }) {
            throw ApiException.badRequest("Each pointer needs at least one point")
        }
        if (pointers.sumOf { it.size } > MAX_GESTURE_POINTS) {
            throw ApiException.badRequest("At most $MAX_GESTURE_POINTS points per gesture")
        }
        if (pointers.any { path -> path.any { it.tMs < 0 } }) {
            throw ApiException.badRequest("tMs must be >= 0")
        }
    }

    suspend fun gesture(
        cdp: CdpSession,
        pkg: String?,
        request: BrowserGestureRequest,
    ): ActionResult =
        when (request.input) {
            BrowserInput.AUTO,
            BrowserInput.TOUCH -> {
                showTab(cdp, pkg)
                val metrics = cdp.call("Page.getLayoutMetrics")
                val content = contentBounds(pkg)
                val mapped =
                    request.pointers.map { path ->
                        path.map { point ->
                            val screen = screenPoint(point.x, point.y, metrics, content)
                            point.copy(x = screen.x, y = screen.y)
                        }
                    }
                gestureAt(mapped, request.humanize, content)
            }
            BrowserInput.CDP_MOUSE -> throw mouseOnlyTaps("gesture")
            BrowserInput.CDP -> {
                val metrics = cdp.call("Page.getLayoutMetrics")
                val human = cdpHumanGesture(request.pointers, metrics, request.humanize, paths)
                dispatchTouchPath(cdp, human.pointers, human.fingers, axes(), dipScale(metrics))
                cdpPoints(human.pointers)
            }
        }

    /** Resolves a CDP key before the socket opens. Device keys stay on `input=touch`. */
    fun prepareKey(request: BrowserKeyRequest): DomKey? =
        when (request.input) {
            BrowserInput.AUTO,
            BrowserInput.TOUCH -> null
            BrowserInput.CDP -> domKey(request.key, request.metaState)
            BrowserInput.CDP_MOUSE -> throw mouseOnlyTaps("key")
        }

    suspend fun key(
        cdp: CdpSession,
        pkg: String?,
        request: BrowserKeyRequest,
        pageKey: DomKey?,
    ): ActionResult =
        when (request.input) {
            BrowserInput.AUTO,
            BrowserInput.TOUCH -> {
                showTab(cdp, pkg)
                pressKey(
                    KeyRequest(
                        key = request.key,
                        longPress = request.longPress,
                        metaState = request.metaState,
                        autoWake = false,
                    )
                )
            }
            BrowserInput.CDP -> {
                dispatchDomKey(
                    cdp,
                    pageKey ?: domKey(request.key, request.metaState),
                    cdpModifiers(request.metaState),
                    request.longPress,
                )
                ActionResult(ok = true, backend = "cdp")
            }
            BrowserInput.CDP_MOUSE -> throw mouseOnlyTaps("key")
        }

    fun prepareText(request: BrowserTextRequest): TapTarget? {
        if (request.input == BrowserInput.CDP_MOUSE) throw mouseOnlyTaps("text")
        checkText(request)
        return target(request.ref, request.selector)
    }

    suspend fun text(
        cdp: CdpSession,
        id: String,
        pkg: String?,
        request: BrowserTextRequest,
        focus: TapTarget?,
    ): ActionResult {
        if (request.input in TOUCH_INPUTS) showTab(cdp, pkg)
        if (focus != null) focusField(cdp, id, focus, pkg, request.input)
        return when (request.input) {
            BrowserInput.AUTO,
            BrowserInput.TOUCH ->
                typeText(
                    TextRequest(
                        text = request.text,
                        mode = request.mode,
                        clear = request.clear,
                        submit = request.submit,
                        minDelayMs = request.minDelayMs,
                        maxDelayMs = request.maxDelayMs,
                        autoWake = false,
                    )
                )
            BrowserInput.CDP -> {
                dispatchInsertText(cdp, request.text, request.clear, request.submit)
                ActionResult(ok = true, backend = "cdp")
            }
            BrowserInput.CDP_MOUSE -> throw mouseOnlyTaps("text")
        }
    }

    private suspend fun touchTap(
        cdp: CdpSession,
        id: String,
        wanted: TapTarget,
        pkg: String?,
        humanize: Boolean,
    ): ActionResult {
        showTab(cdp, pkg)
        val boxes = quads(cdp, id, wanted)
        val hit = screenTarget(boxes, cdp.call("Page.getLayoutMetrics"), contentBounds(pkg))
        return touchAt(hit, humanize)
    }

    private suspend fun cdpTap(
        cdp: CdpSession,
        id: String,
        wanted: TapTarget,
        humanize: Boolean,
    ): ActionResult {
        val boxes = quads(cdp, id, wanted)
        val metrics = cdp.call("Page.getLayoutMetrics")
        val path = cdpTapPath(boxes, metrics, humanize, paths)
        dispatchTouchPath(
            cdp,
            listOf(path),
            listOf(paths.finger(path.size, FingerMotion.PRESS, humanize)),
            axes(),
            dipScale(metrics),
        )
        val down = path.first()
        return ActionResult(ok = true, backend = "cdp", points = listOf(Point(down.x, down.y)))
    }

    private suspend fun mouseTap(
        cdp: CdpSession,
        id: String,
        wanted: TapTarget,
        humanize: Boolean,
    ): ActionResult {
        val boxes = quads(cdp, id, wanted)
        val path = cdpTapPath(boxes, cdp.call("Page.getLayoutMetrics"), humanize, paths)
        dispatchMouseClick(cdp, path)
        val down = path.first()
        return ActionResult(
            ok = true,
            backend = "cdp-mouse",
            points = listOf(Point(down.x, down.y)),
        )
    }

    private fun mouseOnlyTaps(what: String): ApiException =
        ApiException.badRequest(
            "input cdp-mouse only taps. For a $what, use input cdp with Chrome on screen, " +
                "input touch, or browser_eval."
        )

    private suspend fun axes(): TouchscreenInfo =
        try {
            touchscreen()
        } catch (_: ApiException) {
            fallbackTouchscreen()
        }

    /**
     * Brings the app up, then selects the tab, before every touch input. In that order, since
     * Chrome coming forward can select another tab after this one. Unlike a read's show, this does
     * not wait for the page's text to settle, since input acts on what is there.
     */
    private suspend fun showTab(cdp: CdpSession, pkg: String?) {
        appUp(pkg)
        foreground(cdp)
    }

    /** Selects the tab, and refuses the input if Chrome won't, so it can't land on another. */
    private suspend fun foreground(cdp: CdpSession) {
        cdp.call("Page.bringToFront")
        if (settled(FRONT_MS) { tabShowing(cdp) }) return
        throw ApiException(409, "not_visible", "Chrome did not bring this tab forward.")
    }

    private fun cdpPoints(pointers: List<List<TimedPoint>>): ActionResult {
        val points = pointers.flatMap { path ->
            if (path.size > 1) listOf(path.first(), path.last()) else listOf(path.first())
        }
        return ActionResult(ok = true, backend = "cdp", points = points.map { Point(it.x, it.y) })
    }

    private suspend fun swipeEnds(
        cdp: CdpSession,
        id: String,
        request: BrowserSwipeRequest,
        metrics: JsonObject? = null,
    ): Pair<Point, Point> {
        val from = request.from
        val to = request.to
        if (from != null && to != null) return from to to
        val direction =
            request.direction ?: throw ApiException.badRequest("Pass from and to, or a direction")
        val boxes = target(request.ref, request.selector)?.let { quads(cdp, id, it) }
        val frame = metrics ?: cdp.call("Page.getLayoutMetrics")
        return directionSwipe(boxes, frame, direction, request.distance)
    }

    private suspend fun focusField(
        cdp: CdpSession,
        id: String,
        focus: TapTarget,
        pkg: String?,
        input: BrowserInput,
    ) {
        when (input) {
            BrowserInput.AUTO,
            BrowserInput.TOUCH -> {
                val boxes = quads(cdp, id, focus)
                val rect =
                    screenTarget(boxes, cdp.call("Page.getLayoutMetrics"), contentBounds(pkg))
                val tapped = touchAt(rect, false)
                if (!tapped.ok) {
                    throw ApiException(409, "gesture_cancelled", "The system cancelled the touch")
                }
            }
            BrowserInput.CDP,
            BrowserInput.CDP_MOUSE -> cdp.call("DOM.focus", focusNode(cdp, id, focus))
        }
    }

    private fun checkText(request: BrowserTextRequest) {
        if (request.text.length > MAX_TEXT) {
            throw ApiException.badRequest("text is longer than $MAX_TEXT characters")
        }
        if (
            request.minDelayMs < 0 ||
                request.maxDelayMs < request.minDelayMs ||
                request.maxDelayMs > MAX_DELAY_MS
        ) {
            throw ApiException.badRequest("Need 0 <= minDelayMs <= maxDelayMs <= $MAX_DELAY_MS")
        }
        target(request.ref, request.selector)
    }

    private companion object {
        const val MIN_SWIPE_MS = 20L
        const val MAX_SWIPE_MS = 10_000L
        const val MAX_GESTURE_POINTS = 10_000
        const val MAX_TEXT = 10_000
        const val MAX_DELAY_MS = 5_000L
        const val DEFAULT_REFRESH_HZ = 60f
    }
}
