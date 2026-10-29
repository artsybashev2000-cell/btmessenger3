package ru.btmessenger.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BleAdvertTest {
    @Test
    fun advertFitsIn31Bytes() {
        val size = BleAdvert.encodedSize(UUID.randomUUID().toString())
        assertEquals(30, size)
        assertTrue(size <= BleAdvert.LEGACY_LIMIT)
    }

    @Test
    fun shortIdIsStableAndShort() {
        val id = UUID.randomUUID().toString()
        assertEquals(BleAdvert.SHORT_ID_BYTES, BleAdvert.shortId(id).size)
        assertArrayEquals(BleAdvert.shortId(id), BleAdvert.shortId(id))
        assertFalse(BleAdvert.shortId(id).contentEquals(BleAdvert.shortId(UUID.randomUUID().toString())))
    }

    @Test
    fun parseRoundTrip() {
        val id = UUID.randomUUID().toString()
        val parsed = BleAdvert.parseShortId(BleAdvert.payload(id))
        assertNotNull(parsed)
        assertArrayEquals(BleAdvert.shortId(id), parsed)
    }

    @Test
    fun parseRejectsForeignData() {
        assertNull(BleAdvert.parseShortId(null))
        assertNull(BleAdvert.parseShortId(ByteArray(3)))
        val wrongVersion = BleAdvert.payload("x").also { it[0] = 99 }
        assertNull(BleAdvert.parseShortId(wrongVersion))
    }

    @Test
    fun keyIsHexOfShortId() {
        val id = UUID.randomUUID().toString()
        val key = BleAdvert.key(id)
        assertEquals(BleAdvert.SHORT_ID_BYTES * 2, key.length)
        assertEquals(key, BleAdvert.hex(BleAdvert.parseShortId(BleAdvert.payload(id))!!))
    }
}
