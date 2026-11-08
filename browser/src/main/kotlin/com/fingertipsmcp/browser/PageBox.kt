package com.fingertipsmcp.browser

import com.fingertipsmcp.input.Box
import com.fingertipsmcp.input.HumanGesture
import com.fingertipsmcp.input.Humanizer
import com.fingertipsmcp.input.PLAIN_TAP_HOLD_MS
import com.fingertipsmcp.input.SwipeSpec
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.Point
import com.fingertipsmcp.model.Rect
import com.fingertipsmcp.model.SwipeDirection
import com.fingertipsmcp.model.TimedPoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Maps a snapshot ref onto a screen rectangle. Letter-sized `StaticText` nodes are Chrome splitting
 * one element, so the tap uses the enclosing element's box. Quads are CSS pixels in the visual
 * viewport, the same space as `getBoundingClientRect`. [content] is the WebView's bounds on screen.
 */
internal fun elementBackendId(tree: JsonObject, ref: String): Int {
    val node =
        enclosing(AxIndex(tree), ref) ?: throw ApiException.notFound("No browser node '$ref'")
    return node.backendId()
        ?: throw ApiException.notFound("Browser node '$ref' has no box on the page")
}

private fun enclosing(index: AxIndex, ref: String): JsonObject? {
    var id = ref
    var node = index.node(id) ?: return null
    repeat(MAX_HOPS) {
        if (!textRole(node)) return node
        val parent = nonRootParent(index, id) ?: return node
        id = parent.first
        node = parent.second
    }
    return node
}

private fun nonRootParent(index: AxIndex, id: String): Pair<String, JsonObject>? {
    val up = index.parent(id) ?: return null
    val parent = index.node(up) ?: return null
    if (rootRole(parent)) return null
    return up to parent
}

internal fun screenTarget(quads: JsonArray, metrics: JsonObject, content: Rect): Rect {
    val viewport = viewport(metrics)
    val scaleX = viewport.deviceWidth / viewport.cssWidth
    val scaleY = viewport.deviceHeight / viewport.cssHeight
    var best: Rect? = null
    var bestArea = 0
    for (element in quads) {
        val visible = placed(element as? JsonArray, content, scaleX, scaleY) ?: continue
        val area = (visible.right - visible.left) * (visible.bottom - visible.top)
        if (area > bestArea) {
            best = visible
            bestArea = area
        }
    }
    return best ?: throw ApiException(409, "offscreen", "The node is outside the page on screen")
}

/**
 * A tap in CSS pixels. Humanized taps are built in device pixels with [Humanizer], then scaled
 * back, so the hold, micro-moves, and jitter match a hardware tap. An exact tap is the quad's
 * center, held for [PLAIN_TAP_HOLD_MS].
 */
internal fun cdpTapPath(
    quads: JsonArray,
    metrics: JsonObject,
    humanize: Boolean,
    humanizer: Humanizer,
): List<TimedPoint> {
    val frame = viewport(metrics)
    val box =
        largestCss(quads, CssBox(0.0, 0.0, frame.cssWidth, frame.cssHeight))
            ?: throw ApiException(409, "offscreen", "The node is outside the viewport")
    if (!humanize) {
        val point = cssPoint(box)
        return listOf(
            TimedPoint(point.x, point.y, 0),
            TimedPoint(point.x, point.y, PLAIN_TAP_HOLD_MS),
        )
    }
    val dip = dipScale(frame)
    val rect = deviceRect(box, dip)
    return humanizer.tap(rect, humanize = true).map { cssPoint(it, dip) }
}

/**
 * A swipe in CSS pixels. Humanized swipes run in device pixels so the bow and sub-pixel noise match
 * a hardware swipe, then scale back.
 */
internal fun cdpSwipePath(
    from: Point,
    to: Point,
    spec: SwipeSpec,
    metrics: JsonObject,
    humanizer: Humanizer,
): List<TimedPoint> {
    if (!spec.humanize) return humanizer.swipe(from, to, spec)
    val dip = dipScale(viewport(metrics))
    val path =
        humanizer.swipe(
            Point((from.x * dip.x).toFloat(), (from.y * dip.y).toFloat()),
            Point((to.x * dip.x).toFloat(), (to.y * dip.y).toFloat()),
            spec,
        )
    return path.map { cssPoint(it, dip) }
}

/**
 * A gesture in CSS pixels. Humanized gestures are noised in device pixels, like a hardware gesture,
 * then scaled back. The contact curves are fractions of the touchscreen axes.
 */
internal fun cdpHumanGesture(
    pointers: List<List<TimedPoint>>,
    metrics: JsonObject,
    humanize: Boolean,
    humanizer: Humanizer,
): HumanGesture {
    if (!humanize) return humanizer.humanGesture(pointers, humanize = false)
    val dip = dipScale(viewport(metrics))
    val device = pointers.map { path ->
        path.map { point ->
            TimedPoint((point.x * dip.x).toFloat(), (point.y * dip.y).toFloat(), point.tMs)
        }
    }
    val human = humanizer.humanGesture(device, humanize = true)
    val css =
        human.pointers.map { path ->
            path.map { point ->
                TimedPoint((point.x / dip.x).toFloat(), (point.y / dip.y).toFloat(), point.tMs)
            }
        }
    return human.copy(pointers = css)
}

/** CSS pixels per device pixel, from Chrome's viewport. One value is the usual dip scale. */
internal fun dipScale(metrics: JsonObject): Float = dipScale(viewport(metrics)).x.toFloat()

private fun dipScale(frame: Viewport): Dip =
    Dip(x = frame.deviceWidth / frame.cssWidth, y = frame.deviceHeight / frame.cssHeight)

private fun deviceRect(box: CssBox, dip: Dip): Rect {
    val left = (box.left * dip.x).roundToInt()
    val top = (box.top * dip.y).roundToInt()
    val right = (box.right * dip.x).roundToInt()
    val bottom = (box.bottom * dip.y).roundToInt()
    return Rect(left, top, max(right, left + 1), max(bottom, top + 1))
}

private fun cssPoint(point: TimedPoint, dip: Dip): TimedPoint =
    TimedPoint((point.x / dip.x).toFloat(), (point.y / dip.y).toFloat(), point.tMs)

private data class Dip(val x: Double, val y: Double)

/** CSS viewport pixels of a direction swipe, across the page or inside [quads]. */
internal fun directionSwipe(
    quads: JsonArray?,
    metrics: JsonObject,
    direction: SwipeDirection,
    distance: Float,
): Pair<Point, Point> {
    val frame = viewport(metrics)
    val page = CssBox(0.0, 0.0, frame.cssWidth, frame.cssHeight)
    val box =
        if (quads == null) insetPage(frame)
        else
            largestCss(quads, page)
                ?: throw ApiException(409, "offscreen", "The node is outside the viewport")
    return directionEnds(box, direction, distance)
}

/**
 * Maps a CSS viewport point onto the WebView's screen rectangle. A point off the page is refused:
 * touch there would land on Chrome's toolbar, the system bars, or another app.
 */
internal fun screenPoint(x: Float, y: Float, metrics: JsonObject, content: Rect): Point {
    val frame = viewport(metrics)
    val scaleX = frame.deviceWidth / frame.cssWidth
    val scaleY = frame.deviceHeight / frame.cssHeight
    val point = Point((content.left + x * scaleX).toFloat(), (content.top + y * scaleY).toFloat())
    if (
        point.x !in content.left.toFloat()..content.right.toFloat() ||
            point.y !in content.top.toFloat()..content.bottom.toFloat()
    ) {
        throw ApiException(409, "offscreen", "The point ($x, $y) is outside the page on screen")
    }
    return point
}

private fun placed(quad: JsonArray?, content: Rect, scaleX: Double, scaleY: Double): Rect? {
    val box = quad?.let { box(it, content, scaleX, scaleY) } ?: return null
    return intersection(box, content)
}

private fun box(quad: JsonArray, content: Rect, scaleX: Double, scaleY: Double): Rect? {
    if (quad.size != QUAD_COORDS) return null
    val coords = quad.map { (it as? JsonPrimitive)?.doubleOrNull ?: return null }
    val xs =
        (0 until QUAD_COORDS step 2).map { i ->
            content.left + (coords[i] * scaleX).roundToInt()
        }
    val ys =
        (1 until QUAD_COORDS step 2).map { i ->
            content.top + (coords[i] * scaleY).roundToInt()
        }
    val left = xs.min()
    val top = ys.min()
    val right = xs.max()
    val bottom = ys.max()
    if (right - left < MIN_EDGE || bottom - top < MIN_EDGE) return null
    return Rect(left, top, right, bottom)
}

/** The CSS viewport, origin at its top left. */
internal fun viewportBox(metrics: JsonObject): Box {
    val frame = viewport(metrics)
    return Box(0f, 0f, frame.cssWidth.toFloat(), frame.cssHeight.toFloat())
}

/** The largest quad's part inside the viewport, or null when none of it is. */
internal fun visibleBox(quads: JsonArray, metrics: JsonObject): Box? {
    val frame = viewport(metrics)
    return largestCss(quads, CssBox(0.0, 0.0, frame.cssWidth, frame.cssHeight))?.toBox()
}

/** The largest quad's bounds wherever it is, inside the viewport or not. */
internal fun wholeBox(quads: JsonArray): Box? =
    quads
        .mapNotNull { (it as? JsonArray)?.let(::cssBox) }
        .maxByOrNull { it.width * it.height }
        ?.toBox()

private fun CssBox.toBox(): Box =
    Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

private fun largestCss(quads: JsonArray, view: CssBox): CssBox? {
    var best: CssBox? = null
    var bestArea = 0.0
    for (element in quads) {
        val visible = cssPlaced(element as? JsonArray, view) ?: continue
        val area = visible.width * visible.height
        if (area > bestArea) {
            best = visible
            bestArea = area
        }
    }
    return best
}

private fun insetPage(frame: Viewport): CssBox {
    val mx = frame.cssWidth * PAGE_MARGIN
    val my = frame.cssHeight * PAGE_MARGIN
    return CssBox(mx, my, frame.cssWidth - mx, frame.cssHeight - my)
}

private fun directionEnds(
    box: CssBox,
    direction: SwipeDirection,
    distance: Float,
): Pair<Point, Point> {
    val span = distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    val cx = ((box.left + box.right) / 2).toFloat()
    val cy = ((box.top + box.bottom) / 2).toFloat()
    val dx = (box.width * span / 2).toFloat()
    val dy = (box.height * span / 2).toFloat()
    val start =
        when (direction) {
            SwipeDirection.UP -> Point(cx, cy + dy)
            SwipeDirection.DOWN -> Point(cx, cy - dy)
            SwipeDirection.LEFT -> Point(cx + dx, cy)
            SwipeDirection.RIGHT -> Point(cx - dx, cy)
        }
    val end =
        when (direction) {
            SwipeDirection.UP -> Point(cx, cy - dy)
            SwipeDirection.DOWN -> Point(cx, cy + dy)
            SwipeDirection.LEFT -> Point(cx - dx, cy)
            SwipeDirection.RIGHT -> Point(cx + dx, cy)
        }
    return start to end
}

private fun cssPlaced(quad: JsonArray?, view: CssBox): CssBox? {
    val box = quad?.let(::cssBox) ?: return null
    return cssIntersection(box, view)
}

private fun cssBox(quad: JsonArray): CssBox? {
    if (quad.size != QUAD_COORDS) return null
    val coords = quad.map { (it as? JsonPrimitive)?.doubleOrNull ?: return null }
    val xs = (0 until QUAD_COORDS step 2).map { coords[it] }
    val ys = (1 until QUAD_COORDS step 2).map { coords[it] }
    val left = xs.min()
    val top = ys.min()
    val right = xs.max()
    val bottom = ys.max()
    if (right - left < MIN_CSS_EDGE || bottom - top < MIN_CSS_EDGE) return null
    return CssBox(left, top, right, bottom)
}

private fun cssIntersection(a: CssBox, b: CssBox): CssBox? {
    val left = max(a.left, b.left)
    val top = max(a.top, b.top)
    val right = min(a.right, b.right)
    val bottom = min(a.bottom, b.bottom)
    if (right - left < MIN_CSS_EDGE || bottom - top < MIN_CSS_EDGE) return null
    return CssBox(left, top, right, bottom)
}

private fun cssPoint(box: CssBox): Point {
    val cx = ((box.left + box.right) / 2).toFloat()
    val cy = ((box.top + box.bottom) / 2).toFloat()
    return Point(cx, cy)
}

private data class CssBox(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double
        get() = right - left

    val height: Double
        get() = bottom - top
}

private fun intersection(a: Rect, b: Rect): Rect? {
    val left = max(a.left, b.left)
    val top = max(a.top, b.top)
    val right = min(a.right, b.right)
    val bottom = min(a.bottom, b.bottom)
    if (right - left < MIN_EDGE || bottom - top < MIN_EDGE) return null
    return Rect(left, top, right, bottom)
}

private fun positive(vararg values: Double): Boolean = values.all { it > 0.0 }

private fun viewport(metrics: JsonObject): Viewport {
    val css = metrics.obj("cssVisualViewport") ?: metrics.obj("cssLayoutViewport")
    val device = metrics.obj("visualViewport") ?: metrics.obj("layoutViewport")
    val cssWidth = css?.num("clientWidth") ?: 0.0
    val cssHeight = css?.num("clientHeight") ?: 0.0
    val deviceWidth = device?.num("clientWidth") ?: 0.0
    val deviceHeight = device?.num("clientHeight") ?: 0.0
    if (!positive(cssWidth, cssHeight, deviceWidth, deviceHeight)) {
        throw ApiException(502, "cdp_error", "Chrome did not return viewport metrics")
    }
    return Viewport(
        cssWidth = cssWidth,
        cssHeight = cssHeight,
        deviceWidth = deviceWidth,
        deviceHeight = deviceHeight,
    )
}

private data class Viewport(
    val cssWidth: Double,
    val cssHeight: Double,
    val deviceWidth: Double,
    val deviceHeight: Double,
)

private class AxIndex(tree: JsonObject) {
    private val byId = HashMap<String, JsonObject>()
    private val parentOf = HashMap<String, String>()

    init {
        val nodes = tree["nodes"] as? JsonArray ?: JsonArray(emptyList())
        nodes.forEach(::add)
    }

    private fun add(element: JsonElement) {
        val node = element as? JsonObject ?: return
        val id = node.string("nodeId") ?: return
        byId[id] = node
        val children = node["childIds"] as? JsonArray ?: return
        for (child in children) parentId(id, child)
    }

    private fun parentId(id: String, child: JsonElement) {
        val childId = (child as? JsonPrimitive)?.contentOrNull ?: return
        parentOf[childId] = id
    }

    fun node(id: String): JsonObject? = byId[id]

    fun parent(id: String): String? = parentOf[id]
}

private fun textRole(node: JsonObject): Boolean = node.stringValue("role") in TEXT_ROLES

private fun rootRole(node: JsonObject): Boolean = node.stringValue("role") == "RootWebArea"

private fun JsonObject.backendId(): Int? {
    val value = this["backendDOMNodeId"] as? JsonPrimitive ?: return null
    return value.doubleOrNull?.toInt()
}

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.num(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringValue(name: String): String? {
    val value = (this[name] as? JsonObject)?.get("value") ?: return null
    return (value as? JsonPrimitive)?.contentOrNull
}

private val TEXT_ROLES = setOf("StaticText", "InlineTextBox")

private const val QUAD_COORDS = 8
private const val MIN_EDGE = 2
private const val MIN_CSS_EDGE = 1.0
private const val PAGE_MARGIN = 0.12
private const val MIN_DISTANCE = 0.05f
private const val MAX_DISTANCE = 0.95f
private const val MAX_HOPS = 40
