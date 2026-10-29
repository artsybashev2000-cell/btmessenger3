package ru.btmessenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class StillnessLogicTest {
    private val rnd = Random(42)
    private val g = 9.81
    private val dt = 200_000_000L // 5 Гц

    private class Result {
        var seconds = -1
        var kind: StillKind? = null
        var calls = 0
        var moved = 0
    }

    private fun logic(r: Result, thr: Int = 60, ignoreSurface: Boolean = true, s: StillSensitivity = StillSensitivity.MEDIUM) =
        StillnessLogic(
            thresholdSec = thr, sensitivity = s, ignoreSurface = ignoreSurface,
            onStill = { sec, k -> r.seconds = sec; r.kind = k; r.calls++ },
            onMoved = { r.moved++ }
        )

    /** Подаёт [sec] секунд отсчётов с шумом [noise] (м/с²) и наклоном [tiltDeg] от вертикали; возвращает новое время. */
    private fun feed(l: StillnessLogic, t0: Long, sec: Int, noise: Double, tiltDeg: Double = 0.0, extra: (Int) -> Double = { 0.0 }): Long {
        var t = t0
        val steps = sec * 5
        val tilt = Math.toRadians(tiltDeg)
        for (i in 0 until steps) {
            val m = g + extra(i)
            l.onSample(t, m * Math.sin(tilt) + rnd.nextGaussian() * noise, rnd.nextGaussian() * noise, m * Math.cos(tilt) + rnd.nextGaussian() * noise)
            t += dt
        }
        return t
    }

    @Test
    fun phoneOnTableIsIgnoredByDefault() {
        val r = Result()
        val l = logic(r)
        feed(l, 0, 200, 0.005)
        assertEquals(0, r.calls)
        assertEquals(StillKind.ON_SURFACE, l.kind)
    }

    @Test
    fun phoneOnTableFiresWhenIgnoreIsOff() {
        val r = Result()
        val l = logic(r, ignoreSurface = false)
        feed(l, 0, 200, 0.005)
        assertEquals(1, r.calls) // один раз за период
        assertTrue(r.seconds in 60..70)
        assertEquals(StillKind.ON_SURFACE, r.kind)
    }

    @Test
    fun heldPhoneWithTremorFires() {
        val r = Result()
        val l = logic(r)
        feed(l, 0, 100, 0.05, tiltDeg = 35.0) // в руке: наклон и лёгкая дрожь
        assertEquals(1, r.calls)
        assertEquals(StillKind.CARRIED, r.kind)
    }

    @Test
    fun quietButTiltedPhoneIsUnknownAndFires() {
        val r = Result()
        val l = logic(r)
        feed(l, 0, 100, 0.005, tiltDeg = 40.0) // карман или прислонён: дрожи нет
        assertEquals(1, r.calls)
        assertEquals(StillKind.UNKNOWN, r.kind)
    }

    @Test
    fun walkingNeverFires() {
        val r = Result()
        val l = logic(r)
        feed(l, 0, 300, 0.3) { i -> 3.0 * Math.sin(i * 1.2) }
        assertEquals(0, r.calls)
    }

    @Test
    fun movementResetsTheCountdownAndReportsAfterAlert() {
        val r = Result()
        val l = logic(r)
        var t = feed(l, 0, 40, 0.05, tiltDeg = 35.0)
        t = feed(l, t, 10, 0.5) { i -> 3.0 * Math.sin(i * 1.2) } // пошевелился: отсчёт заново
        assertEquals(0, r.calls)
        t = feed(l, t, 100, 0.05, tiltDeg = 35.0)
        assertEquals(1, r.calls)
        feed(l, t, 10, 0.5) { i -> 3.0 * Math.sin(i * 1.2) }
        assertEquals(1, r.moved)
    }

    @Test
    fun slowTiltCountsAsMovement() {
        val r = Result()
        val l = logic(r)
        // тихо, но телефон медленно поворачивают: 1° за 5 с на протяжении 200 с
        var t = 0L
        for (w in 0 until 40) t = feed(l, t, 5, 0.01, tiltDeg = 30.0 + w)
        assertEquals(0, r.calls)
    }

    @Test
    fun sensorGapResetsAndDoesNotCountAsStill() {
        val r = Result()
        val l = logic(r, thr = 60)
        var t = feed(l, 0, 40, 0.05, tiltDeg = 35.0)
        t += 30_000_000_000L // датчик молчал 30 с
        t = feed(l, t, 40, 0.05, tiltDeg = 35.0)
        assertEquals(0, r.calls) // 40 + 40 с с пропуском не считаются за 80 с подряд
        feed(l, t, 40, 0.05, tiltDeg = 35.0)
        assertEquals(1, r.calls)
    }

    @Test
    fun highSensitivityIsBumpedByTinyMotion() {
        val low = Result()
        val high = Result()
        val a = logic(low, s = StillSensitivity.LOW)
        val b = logic(high, s = StillSensitivity.HIGH)
        // дрожь около 0,1 м/с²: для низкой чувствительности это покой, для высокой движение
        feed(a, 0, 100, 0.1, tiltDeg = 35.0)
        feed(b, 0, 100, 0.1, tiltDeg = 35.0)
        assertEquals(1, low.calls)
        assertEquals(0, high.calls)
    }

    @Test
    fun rearmStartsCountdownAgain() {
        val r = Result()
        val l = logic(r)
        var t = feed(l, 0, 100, 0.05, tiltDeg = 35.0)
        assertEquals(1, r.calls)
        l.rearm()
        t = feed(l, t, 30, 0.05, tiltDeg = 35.0)
        assertEquals(1, r.calls)
        feed(l, t, 60, 0.05, tiltDeg = 35.0)
        assertEquals(2, r.calls)
        assertFalse(r.seconds < 60)
    }
}
