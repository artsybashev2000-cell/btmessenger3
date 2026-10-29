package ru.btmessenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSightingsTest {
    private val ttl = 30_000L

    @Test
    fun newPhoneIsNearbyThenExpires() {
        val s = BleSightings(ttl)
        assertTrue(s.update("aa", "AA:BB", -60, 0))
        assertEquals(1, s.fresh(1_000).size)
        assertEquals(-60, s.get("aa", 1_000)?.rssi)
        assertNull(s.get("aa", ttl + 1))
        assertTrue(s.fresh(ttl + 1).isEmpty())
    }

    @Test
    fun repeatedAdvertsAreMergedByKeyEvenIfAddressChanges() {
        val s = BleSightings(ttl)
        s.update("aa", "AA:01", -60, 0)
        s.update("aa", "AA:02", -60, 1_000) // у телефона сменился случайный адрес
        assertEquals(1, s.fresh(2_000).size)
        assertEquals("AA:02", s.get("aa", 2_000)?.bleAddress)
    }

    @Test
    fun rssiIsSmoothedAndSmallChangesDoNotRedraw() {
        val s = BleSightings(ttl)
        s.update("aa", "AA:01", -60, 0)
        assertFalse(s.update("aa", "AA:01", -63, 1_000)) // -61: сдвиг меньше порога
        assertEquals(-61, s.get("aa", 1_000)?.rssi)
        assertTrue(s.update("aa", "AA:01", -90, 2_000)) // заметный скачок
    }

    @Test
    fun pruneReportsOnlyOnceWhenSomeoneDisappears() {
        val s = BleSightings(ttl)
        s.update("aa", "AA:01", -60, 0)
        assertFalse(s.prune(1_000))
        assertTrue(s.prune(ttl + 1))
        assertFalse(s.prune(ttl + 2))
    }

    @Test
    fun comesBackAfterExpiry() {
        val s = BleSightings(ttl)
        s.update("aa", "AA:01", -60, 0)
        s.prune(ttl + 1)
        assertTrue(s.update("aa", "AA:01", -70, ttl + 5))
        assertEquals(-70, s.get("aa", ttl + 5)?.rssi) // после перерыва сглаживание начинается заново
    }
}
