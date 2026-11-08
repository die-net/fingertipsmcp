package com.fingertipsmcp.browser

import com.fingertipsmcp.core.settled
import com.fingertipsmcp.input.checkGesturePointers
import com.fingertipsmcp.input.checkSwipeDuration
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserGestureRequest
import com.fingertipsmcp.model.BrowserInput
import com.fingertipsmcp.model.BrowserKeyRequest
import com.fingertipsmcp.model.BrowserSwipeRequest
import com.fingertipsmcp.model.BrowserTapRequest
import com.fingertipsmcp.model.BrowserTextRequest

/**
 * How one platform delivers browser input. Android ([PageInput]) taps the screen or sends touch
 * events; a desktop ([DesktopPageInput]) sends mouse and keyboard events. The `check` and `prepare`
 * calls run before the page session opens, so a bad request fails without one.
 */
@Suppress("ComplexInterface") // Each kind of input is checked, then played, per platform.
internal interface PageInputs {
    /** Runs on the page's session before any action, once the browser service is ready. */
    suspend fun ready(cdp: CdpSession, input: BrowserInput, pointer: Boolean)

    suspend fun tap(
        cdp: CdpSession,
        id: String,
        wanted: TapTarget,
        pkg: String?,
        request: BrowserTapRequest,
    ): ActionResult

    fun checkSwipe(request: BrowserSwipeRequest)

    suspend fun swipe(
        cdp: CdpSession,
        id: String,
        pkg: String?,
        request: BrowserSwipeRequest,
    ): ActionResult

    fun checkGesture(request: BrowserGestureRequest)

    suspend fun gesture(cdp: CdpSession, pkg: String?, request: BrowserGestureRequest): ActionResult

    /** Resolves a DOM key before the session opens, or null when the platform sends it itself. */
    fun prepareKey(request: BrowserKeyRequest): DomKey?

    suspend fun key(
        cdp: CdpSession,
        pkg: String?,
        request: BrowserKeyRequest,
        pageKey: DomKey?,
    ): ActionResult

    /** Checks text input and returns the field to focus, if any. */
    fun prepareText(request: BrowserTextRequest): TapTarget?

    suspend fun text(
        cdp: CdpSession,
        id: String,
        pkg: String?,
        request: BrowserTextRequest,
        focus: TapTarget?,
    ): ActionResult
}

/** The swipe checks every platform makes: duration, and from and to or a direction. */
internal fun checkSwipeRequest(request: BrowserSwipeRequest) {
    checkSwipeDuration(request.durationMs)
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
    optionalTarget(request.ref, request.selector)
}

/** The gesture checks every platform makes: point counts and times. */
internal fun checkGestureRequest(request: BrowserGestureRequest) {
    checkGesturePointers(request.pointers)
}

/**
 * Selects the tab, and refuses the input if Chrome won't within [FRONT_MS], so it can't land on
 * another tab.
 */
internal suspend fun selectTab(cdp: CdpSession) {
    cdp.call("Page.bringToFront")
    if (settled(FRONT_MS) { tabShowing(cdp) }) return
    throw ApiException(409, "not_visible", "Chrome did not bring this tab forward.")
}

/**
 * The text checks every platform makes: length and the per-key delay range. Returns the field to
 * focus, if any.
 */
internal fun checkTextRequest(request: BrowserTextRequest): TapTarget? {
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
    return optionalTarget(request.ref, request.selector)
}

private const val MAX_TEXT = 10_000
private const val MAX_DELAY_MS = 5_000L
