package ru.btmessenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Чистые части хранения голосовых сообщений и показа их длины (без Android). */
class VoiceStoreTest {

    /** Кадр AMR-WB нужного режима: заголовок (режим + бит качества) и нули вместо звука. */
    private fun frame(mode: Int = 2): ByteArray {
        val size = intArrayOf(18, 24, 33, 37, 41, 47, 51, 59, 61)[mode]
        return ByteArray(size).also { it[0] = ((mode shl 3) or 4).toByte() }
    }

    private fun msg(text: String) = ChatMessage(
        id = "v1", peer = "AA:BB", fromMe = true, kind = Kind.VOICE, text = text,
        geo = null, sentAt = 1L, receivedAt = 1L, status = Status.PENDING
    )

    @Test
    fun safeIdAcceptsOurIdsAndRejectsPaths() {
        assertTrue(VoiceStore.isSafeId("a1b2c3d4e5f60718"))
        assertTrue(VoiceStore.isSafeId("id-1_x"))
        assertFalse(VoiceStore.isSafeId(""))
        assertFalse(VoiceStore.isSafeId("../etc/passwd"))
        assertFalse(VoiceStore.isSafeId("a/b"))
        assertFalse(VoiceStore.isSafeId("a.b"))
        assertFalse(VoiceStore.isSafeId("я"))
        assertFalse(VoiceStore.isSafeId("x".repeat(65)))
    }

    @Test
    fun regroupKeepsEveryFrameAndByte() {
        val frames = List(5) { frame() }
        val chunks = VoiceStore.regroup(frames, 2)
        assertEquals(3, chunks.size) // 2 + 2 + 1
        assertEquals(5 * 33, chunks.sumOf { it.size })
        assertEquals(5, chunks.flatMap { AmrWb.split(it) }.size)
    }

    @Test
    fun regroupOfNothingIsNothing() {
        assertTrue(VoiceStore.regroup(emptyList()).isEmpty())
    }

    @Test
    fun durationCountsTwentyMsPerFrame() {
        val chunks = listOf(frame() + frame(), frame() + frame() + frame())
        assertEquals(100, VoiceStore.durationMs(chunks))
    }

    @Test
    fun durationIgnoresBrokenTail() {
        // хвост из одного байта с недопустимым режимом (10) отбрасывается
        val bad = byteArrayOf((10 shl 3).toByte())
        assertEquals(20, VoiceStore.durationMs(listOf(frame() + bad)))
    }

    @Test
    fun lengthIsShownAsMinutesAndSeconds() {
        assertEquals("0:00", Format.mmss(0L))
        assertEquals("0:07", Format.mmss(7_000L))
        assertEquals("0:08", Format.mmss(7_600L))
        assertEquals("1:05", Format.mmss(65_000L))
        assertEquals("0:00", Format.mmss(-5L))
    }

    @Test
    fun voiceMsIsReadFromText() {
        assertEquals(7200, msg("7200").voiceMs)
        assertEquals(0, msg("").voiceMs)
        assertEquals(0, msg("мусор").voiceMs)
        assertEquals(0, msg("-5").voiceMs)
    }

    @Test
    fun voiceKindHasItsOwnCodeAndIsNotAcceptedFromWire() {
        assertEquals(Kind.VOICE, Kind.of("voice"))
        // голос по msg не ходит: такой кадр читается как обычный текст
        val j = org.json.JSONObject("""{"t":"msg","id":"k1","ts":1,"k":"voice","text":"hi"}""")
        val m = WireMessage.fromJson(j)!!
        assertEquals(Kind.TEXT, m.kind)
    }
}
