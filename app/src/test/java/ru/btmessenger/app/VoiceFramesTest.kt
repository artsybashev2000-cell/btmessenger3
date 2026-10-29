package ru.btmessenger.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Random

/** Голосовые кадры: кодирование без потерь, мусор отбрасывается, размер кадра известен. */
class VoiceFramesTest {

    private fun wire(f: Frame): ByteArray {
        val bos = ByteArrayOutputStream()
        Protocol.write(DataOutputStream(bos), f)
        return bos.toByteArray()
    }

    private fun roundTrip(f: Frame): Frame = Protocol.read(DataInputStream(ByteArrayInputStream(wire(f))))

    private fun parse(json: String): Frame {
        val body = json.toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).apply { writeInt(body.size); write(body) }
        return Protocol.read(DataInputStream(ByteArrayInputStream(bos.toByteArray())))
    }

    private fun isUnknown(f: Frame) = f is Frame.Unknown

    @Test
    fun base64MatchesJdk() {
        val rnd = Random(1)
        for (len in 0..70) {
            val b = ByteArray(len).also { rnd.nextBytes(it) }
            val expected = java.util.Base64.getEncoder().encodeToString(b)
            assertEquals(expected, Base64Lite.encode(b))
            assertArrayEquals(b, Base64Lite.decode(expected))
        }
    }

    @Test
    fun base64RejectsGarbage() {
        assertEquals(null, Base64Lite.decode("abc")) // длина не кратна 4
        assertEquals(null, Base64Lite.decode("ab!d"))
        assertEquals(null, Base64Lite.decode("====")) // только заполнитель
        assertEquals(null, Base64Lite.decode("a=bc")) // «=» посередине
        assertEquals(null, Base64Lite.decode("абвг"))
    }

    @Test
    fun startRoundTrip() {
        val f = Frame.VoiceStart("abc123", true, Protocol.VOICE_CODEC_AMR_WB, 40, 1_700_000_000_000L)
        assertEquals(f, roundTrip(f))
        val m = Frame.VoiceStart("abc123", false, Protocol.VOICE_CODEC_AMR_WB, 20, 5L)
        assertEquals(m, roundTrip(m))
    }

    @Test
    fun chunkRoundTrip() {
        val data = ByteArray(66) { (it * 7).toByte() }
        val back = roundTrip(Frame.VoiceChunk("abc123", 17, data)) as Frame.VoiceChunk
        assertEquals("abc123", back.id)
        assertEquals(17, back.seq)
        assertArrayEquals(data, back.data)
    }

    @Test
    fun endAndNackRoundTrip() {
        assertEquals(Frame.VoiceEnd("x", 1500), roundTrip(Frame.VoiceEnd("x", 1500)))
        assertEquals(Frame.VoiceEnd("x", 0), roundTrip(Frame.VoiceEnd("x", 0)))
        assertEquals(Frame.VoiceNack("x", listOf(3, 4, 99)), roundTrip(Frame.VoiceNack("x", listOf(3, 4, 99))))
    }

    @Test
    fun unknownCodecIsKept() {
        // кадр разбирается, а решает, играть ли такой звук, получатель (BtLink)
        val f = parse("""{"t":"voice_start","id":"a","live":true,"codec":"opus","ms":40}""") as Frame.VoiceStart
        assertEquals("opus", f.codec)
    }

    @Test
    fun badFramesBecomeUnknown() {
        assertTrue(isUnknown(parse("""{"t":"voice_start","ms":40}""")))                            // нет id
        assertTrue(isUnknown(parse("""{"t":"voice_start","id":"a"}""")))                           // нет длины куска
        assertTrue(isUnknown(parse("""{"t":"voice_start","id":"a","ms":5000}""")))                 // длина вне 10..200
        assertTrue(isUnknown(parse("""{"t":"voice_chunk","id":"a","n":-1,"d":"AAAA"}""")))         // отрицательный номер
        assertTrue(isUnknown(parse("""{"t":"voice_chunk","id":"a","n":1}""")))                     // нет данных
        assertTrue(isUnknown(parse("""{"t":"voice_chunk","id":"a","n":1,"d":"!!!!"}""")))          // не base64
        assertTrue(isUnknown(parse("""{"t":"voice_chunk","n":1,"d":"AAAA"}""")))                   // нет id
        assertTrue(isUnknown(parse("""{"t":"voice_chunk","id":"a","n":2000000,"d":"AAAA"}""")))    // номер за пределом
        assertTrue(isUnknown(parse("""{"t":"voice_end","id":"a"}""")))                             // нет счётчика
        assertTrue(isUnknown(parse("""{"t":"voice_end","id":"a","n":-3}""")))
        assertTrue(isUnknown(parse("""{"t":"voice_nack","id":"a","missing":[]}""")))               // пустой список
        assertTrue(isUnknown(parse("""{"t":"voice_nack","id":"a"}""")))
    }

    @Test
    fun oversizedChunkIsRejected() {
        val big = Base64Lite.encode(ByteArray(Protocol.MAX_VOICE_CHUNK_BYTES + 1) { 1 })
        assertTrue(isUnknown(parse("""{"t":"voice_chunk","id":"a","n":1,"d":"$big"}""")))
        val ok = Base64Lite.encode(ByteArray(Protocol.MAX_VOICE_CHUNK_BYTES) { 1 })
        assertTrue(parse("""{"t":"voice_chunk","id":"a","n":1,"d":"$ok"}""") is Frame.VoiceChunk)
    }

    @Test
    fun nackKeepsOnlyValidNumbers() {
        val f = parse("""{"t":"voice_nack","id":"a","missing":[1,-5,"x",99999,7]}""") as Frame.VoiceNack
        assertEquals(listOf(1, 7), f.missing)
    }

    @Test
    fun chunkFrameSizeOnWire() {
        // реальный кусок 40 мс (два кадра AMR-WB по 33 байта) и id из 16 символов
        val bytes = wire(Frame.VoiceChunk("0123456789abcdef", 1234, ByteArray(66) { 5 }))
        assertNotNull(bytes)
        assertTrue("кадр ${bytes.size} байт", bytes.size in 140..160) // сейчас 151: 4 длина + ~60 JSON + 88 base64
    }

    @Test
    fun oldPhoneIgnoresVoiceFramesAsUnknown() {
        // старая версия читает кадр по полю "t": незнакомый тип = Unknown, соединение живёт
        val f = parse("""{"t":"voice_chunk_v9","id":"a"}""")
        assertTrue(f is Frame.Unknown)
    }
}
