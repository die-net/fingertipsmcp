package com.fingertipsmcp.input

import com.fingertipsmcp.model.Point
import com.fingertipsmcp.model.Rect
import com.fingertipsmcp.model.TimedPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HumanizerTest {
    private val target = Rect(100, 200, 300, 300)

    @Test
    fun `steady fallback contact`() {
        val contact = FingerContact.fallback
        assertEquals(0.55f, contact.pressure, 0.0001f)
        assertEquals(0.08f, contact.size, 0.0001f)
        assertEquals(0.08f, contact.touchMajor, 0.0001f)
        assertEquals(0.08f, contact.touchMinor, 0.0001f)
        assertEquals(0f, contact.orientation, 0.0001f)
        val cdp = contact.toCdp(2.5f)
        assertEquals(0.016f, cdp.radiusX, 0.0001f)
        assertEquals(0.016f, cdp.radiusY, 0.0001f)
        assertEquals(0.55f, cdp.force, 0.0001f)
        assertEquals(0f, cdp.rotationAngle, 0.0001f)
    }

    @Test
    fun `tap contact grows then eases`() {
        repeat(100) { seed ->
            val human = Humanizer(Random(seed))
            val path = human.tap(target)
            val finger = human.finger(path.size, FingerMotion.PRESS, humanize = true)
            assertEquals(path.size, finger.size)
            finger.forEach { shape ->
                assertTrue(shape.major > shape.minor, "major=${shape.major} minor=${shape.minor}")
                assertTrue(shape.pressure in 0.25f..0.85f, "pressure=${shape.pressure}")
                assertTrue(shape.major in 0.035f..0.16f, "major=${shape.major}")
                assertTrue(shape.orientation in 0.42f..0.58f)
            }
            val peak = finger.indices.maxBy { finger[it].pressure }
            assertTrue(peak != 0 && peak != finger.lastIndex, "peak=$peak pressures=$finger")
            finger.zipWithNext().forEach { (a, b) ->
                assertTrue(abs(a.pressure - b.pressure) < 0.2f)
                assertTrue(abs(a.orientation - b.orientation) < 0.05f)
            }
        }
    }

    @Test
    fun `swipe contact stays in a band`() {
        val from = Point(0f, 0f)
        val to = Point(0f, 900f)
        repeat(40) { seed ->
            val human = Humanizer(Random(seed))
            val path = human.swipe(from, to, SwipeSpec(fling = true))
            val finger = human.finger(path.size, FingerMotion.FLING, humanize = true)
            val pressures = finger.map { it.pressure }
            assertTrue(pressures.max() - pressures.min() > 0.005f)
            val span = pressures.max() - pressures.min()
            assertTrue(span < 0.3f, "span=$span")
            assertTrue(finger.all { it.major > it.minor })
            assertTrue(pressures.last() > pressures.min())
        }
    }

    @Test
    fun `plain contact stays fixed`() {
        val human = Humanizer(Random(1))
        val path = human.tap(target, humanize = false)
        val finger = human.finger(path.size, FingerMotion.PRESS, humanize = false)
        assertTrue(finger.all { it == FingerShape.steady })
        val dragged = Humanizer(Random(2)).finger(12, FingerMotion.DRAG, humanize = false)
        assertTrue(dragged.all { it == FingerShape.steady })
    }

    @Test
    fun `same seed, same contact`() {
        val a = Humanizer(Random(9)).finger(6, FingerMotion.DRAG, humanize = true)
        val b = Humanizer(Random(9)).finger(6, FingerMotion.DRAG, humanize = true)
        assertEquals(a, b)
    }

    @Test
    fun `plain gesture stays exact`() {
        val path = listOf(TimedPoint(1f, 2f, 0), TimedPoint(4f, 5f, 40), TimedPoint(8f, 9f, 80))
        val human = Humanizer(Random(1)).humanGesture(listOf(path), humanize = false)
        assertEquals(listOf(path), human.pointers)
        assertEquals(listOf(List(path.size) { FingerShape.steady }), human.fingers)
    }

    @Test
    fun `gesture keeps its endpoints`() {
        val path =
            listOf(
                TimedPoint(0f, 0f, 0),
                TimedPoint(0f, 100f, 40),
                TimedPoint(0f, 200f, 80),
                TimedPoint(0f, 280f, 140),
            )
        repeat(20) { seed ->
            val human = Humanizer(Random(seed)).humanGesture(listOf(path), humanize = true)
            val out = human.pointers.single()
            assertEquals(path.first().x, out.first().x)
            assertEquals(path.first().y, out.first().y)
            assertEquals(path.last().x, out.last().x)
            assertEquals(path.last().y, out.last().y)
            out.zip(path).forEach { (actual, original) ->
                assertTrue(abs(actual.x - original.x) < 3f)
                assertTrue(abs(actual.y - original.y) < 3f)
                assertEquals(original.tMs, actual.tMs)
            }
            assertTrue(human.fingers.single().all { it.major > it.minor })
        }
    }

    @Test
    fun `held gesture eases its contact`() {
        val path =
            listOf(
                TimedPoint(10f, 10f, 0),
                TimedPoint(10f, 10f, 30),
                TimedPoint(10f, 11f, 60),
                TimedPoint(10f, 10f, 90),
            )
        val finger =
            Humanizer(Random(4)).humanGesture(listOf(path), humanize = true).fingers.single()
        val peak = finger.indices.maxBy { finger[it].pressure }
        assertTrue(peak != 0 && peak != finger.lastIndex, "peak=$peak")
    }

    @Test
    fun `timeline keeps sample contact`() {
        val path = listOf(TimedPoint(1f, 2f, 0), TimedPoint(1f, 3f, 30), TimedPoint(1f, 4f, 60))
        val shapes =
            listOf(
                FingerShape.steady,
                FingerShape.steady.copy(pressure = 0.7f),
                FingerShape.steady.copy(pressure = 0.4f),
            )
        val samples = touchTimeline(listOf(path), listOf(shapes))
        assertEquals(listOf(0.55f, 0.7f, 0.4f), samples.map { it.shape.pressure })
        assertEquals(
            listOf(TouchPhase.DOWN, TouchPhase.MOVE, TouchPhase.UP),
            samples.map { it.phase },
        )
    }

    @Test
    fun `tap point and hold ranges`() {
        repeat(500) { seed ->
            val path = Humanizer(Random(seed)).tap(target)
            val down = path.first()
            assertTrue(down.x in 140f..260f, "x=${down.x}")
            assertTrue(down.y in 220f..280f, "y=${down.y}")
            assertEquals(0L, down.tMs)
            assertTrue(path.last().tMs in 50L..120L, "hold=${path.last().tMs}")
            assertIncreasing(path)
        }
    }

    @Test
    fun `tap has 1-3 tiny micro-moves`() {
        repeat(200) { seed ->
            val path = Humanizer(Random(seed)).tap(target)
            val moves = path.size - 2
            assertTrue(moves in 1..3, "moves=$moves")
            path.zipWithNext().forEach { (a, b) ->
                assertTrue(abs(a.x - b.x) <= 1f && abs(a.y - b.y) <= 1f)
            }
        }
    }

    @Test
    fun `tap honors requested hold`() {
        val path = Humanizer(Random(1)).tap(target, holdMs = 1_000)
        assertTrue(path.last().tMs in 950L..1_050L)
    }

    @Test
    fun `tap is spread around target`() {
        val xs = (0 until 200).map { Humanizer(Random(it)).tap(target).first().x }.toSet()
        assertTrue(xs.size > 100)
    }

    @Test
    fun `plain tap is centered`() {
        val path = Humanizer(Random(3)).tap(target, humanize = false)
        assertEquals(listOf(TimedPoint(200f, 250f, 0), TimedPoint(200f, 250f, 60)), path)
    }

    @Test
    fun `double tap gap and spread`() {
        repeat(200) { seed ->
            val strokes = Humanizer(Random(seed)).taps(target, count = 2)
            assertEquals(2, strokes.size)
            val gap = strokes[1].first().tMs - strokes[0].last().tMs
            assertTrue(gap in 90L..160L, "gap=$gap")
            val d = distance(strokes[0].first(), strokes[1].first())
            assertTrue(d < 15f, "second tap drifted $d px")
        }
    }

    @Test
    fun `swipe hits endpoints in order`() {
        val from = Point(500f, 1800f)
        val to = Point(500f, 600f)
        repeat(100) { seed ->
            val path = Humanizer(Random(seed)).swipe(from, to, SwipeSpec(durationMs = 300))
            assertEquals(from.x, path.first().x)
            assertEquals(from.y, path.first().y)
            assertEquals(to.x, path.last().x, 0.01f)
            assertEquals(to.y, path.last().y, 0.01f)
            assertIncreasing(path)
            assertTrue(path.last().tMs in 270L..330L)
        }
    }

    @Test
    fun `swipe uses the sample rate`() {
        val path =
            Humanizer(Random(1))
                .swipe(
                    Point(0f, 0f),
                    Point(0f, 1000f),
                    SwipeSpec(durationMs = 500, humanize = false, sampleRateHz = 120f),
                )
        assertEquals(61, path.size)
    }

    @Test
    fun `humanized swipe bows slightly`() {
        repeat(100) { seed ->
            val path =
                Humanizer(Random(seed)).swipe(Point(500f, 2000f), Point(500f, 1000f), SwipeSpec())
            val maxDeviation = path.maxOf { abs(it.x - 500f) }
            assertTrue(maxDeviation in 15f..65f, "deviation=$maxDeviation")
        }
    }

    @Test
    fun `plain swipe is straight`() {
        val path =
            Humanizer(Random(1))
                .swipe(
                    Point(0f, 0f),
                    Point(0f, 600f),
                    SwipeSpec(durationMs = 300, humanize = false),
                )
        assertTrue(path.all { it.x == 0f })
        val speeds = path.zipWithNext().map { (a, b) -> (b.y - a.y) / (b.tMs - a.tMs) }
        assertTrue(speeds.max() - speeds.min() < 0.2f, "speeds=$speeds")
    }

    @Test
    fun `fling releases at speed`() {
        val from = Point(500f, 2000f)
        val to = Point(500f, 800f)
        val fling = Humanizer(Random(7)).swipe(from, to, SwipeSpec(fling = true))
        val settle = Humanizer(Random(7)).swipe(from, to, SwipeSpec(fling = false))
        assertTrue(endSpeed(fling) > 3f, "fling end speed ${endSpeed(fling)}")
        assertTrue(endSpeed(settle) < 0.5f, "settle end speed ${endSpeed(settle)}")
        val settleDwell = settle.last().tMs - settle[settle.lastIndex - 1].tMs
        assertTrue(settleDwell in 40L..90L)
    }

    @Test
    fun `settled swipe eases in-out`() {
        val path =
            Humanizer(Random(9))
                .swipe(Point(0f, 0f), Point(0f, 1200f), SwipeSpec(durationMs = 400, fling = false))
        val speeds = path.dropLast(1).zipWithNext().map { (a, b) -> speed(a, b) }
        val peak = speeds.indexOf(speeds.max())
        assertTrue(speeds.first() < speeds.max() / 3)
        assertTrue(peak in speeds.size / 4..speeds.size * 3 / 4)
    }

    @Test
    fun `same seed gives the same path`() {
        val a = Humanizer(Random(42)).swipe(Point(1f, 2f), Point(300f, 900f))
        val b = Humanizer(Random(42)).swipe(Point(1f, 2f), Point(300f, 900f))
        assertEquals(a, b)
    }

    @Test
    fun `long press outlasts timeout`() {
        val path = Humanizer(Random(5)).longPress(target, longPressTimeoutMs = 400)
        assertTrue(path.last().tMs in 500L..800L)
    }

    private fun endSpeed(path: List<TimedPoint>): Float {
        val window = path.takeLast(3)
        return speed(window.first(), window.last())
    }

    private fun speed(a: TimedPoint, b: TimedPoint) = distance(a, b) / (b.tMs - a.tMs)

    private fun distance(a: TimedPoint, b: TimedPoint) = hypot(b.x - a.x, b.y - a.y)

    private fun assertIncreasing(path: List<TimedPoint>) {
        path.zipWithNext().forEach { (a, b) -> assertTrue(b.tMs > a.tMs, "times $path") }
    }
}
