package ru.btmessenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmrWbTest {
    /** Кадр режима [mode] нужной длины: заголовок (mode shl 3) | 4 и нули. */
    private fun frame(mode: Int): ByteArray {
        val size = AmrWb.frameSize(((mode shl 3) or 4).toByte())
        return ByteArray(size).also { it[0] = ((mode shl 3) or 4).toByte() }
    }

    @Test
    fun frameSizes() {
        assertEquals(33, frame(2).size) // 12,65 кбит/с
        assertEquals(61, frame(8).size) // 23,85 кбит/с
        assertEquals(18, frame(0).size) // 6,60 кбит/с
        assertEquals(0, AmrWb.frameSize(((11 shl 3) or 4).toByte())) // зарезервированный режим
    }

    @Test
    fun splitTwoFrames() {
        val chunk = frame(2) + frame(2)
        val parts = AmrWb.split(chunk)
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.size == 33 })
    }

    @Test
    fun splitMixedModes() {
        val parts = AmrWb.split(frame(0) + frame(8) + frame(2))
        assertEquals(listOf(18, 61, 33), parts.map { it.size })
    }

    @Test
    fun splitDropsTruncatedTail() {
        val chunk = frame(2) + frame(2).copyOf(10)
        assertEquals(1, AmrWb.split(chunk).size)
    }

    @Test
    fun splitEmptyAndGarbage() {
        assertTrue(AmrWb.split(ByteArray(0)).isEmpty())
        assertTrue(AmrWb.split(byteArrayOf(0x58, 1, 2)).isEmpty()) // режим 11 недопустим
    }
}
