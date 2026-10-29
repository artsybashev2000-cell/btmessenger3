package ru.btmessenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTransferTest {

    private fun chunk(n: Int) = ByteArray(10) { n.toByte() }

    // ---------- Сборка сообщения ----------

    @Test
    fun assemblerCompleteInOrder() {
        val a = VoiceAssembler()
        for (i in 0 until 5) assertTrue(a.add(i, chunk(i)))
        assertTrue(a.isComplete(5))
        assertEquals((0 until 5).map { it.toByte() }, a.chunks(5).map { it[0] })
    }

    @Test
    fun assemblerOutOfOrderAndDuplicates() {
        val a = VoiceAssembler()
        for (i in listOf(3, 0, 2, 2, 1, 0)) assertTrue(a.add(i, chunk(i)))
        assertTrue(a.isComplete(4))
        assertEquals(40, a.bytes) // повторы не считаются
        assertEquals(listOf<Byte>(0, 1, 2, 3), a.chunks(4).map { it[0] })
    }

    @Test
    fun assemblerReportsMissing() {
        val a = VoiceAssembler()
        for (i in listOf(0, 1, 4, 6)) a.add(i, chunk(i))
        assertFalse(a.isComplete(7))
        assertEquals(listOf(2, 3, 5), a.missing(7))
        assertEquals(listOf(2, 3), a.missing(7, limit = 2))
        a.add(2, chunk(2)); a.add(3, chunk(3)); a.add(5, chunk(5))
        assertTrue(a.isComplete(7))
        assertTrue(a.missing(7).isEmpty())
    }

    @Test
    fun assemblerShortOfCountIsIncomplete() {
        val a = VoiceAssembler()
        for (i in 0 until 3) a.add(i, chunk(i))
        assertFalse(a.isComplete(4)) // отправитель сказал 4, пришло 3
        assertTrue(a.isComplete(3))
        assertFalse(a.isComplete(0)) // пустое сообщение не бывает полным
    }

    @Test
    fun assemblerLimits() {
        val a = VoiceAssembler(maxChunks = 10, maxBytes = 35)
        assertFalse(a.add(10, chunk(1))) // номер за пределом
        assertFalse(a.add(-1, chunk(1)))
        assertTrue(a.add(0, chunk(0)))
        assertTrue(a.add(1, chunk(1)))
        assertTrue(a.add(2, chunk(2)))
        assertFalse(a.add(3, chunk(3))) // 40 > 35 байт
        assertFalse(a.isComplete(11)) // счётчик больше лимита
    }

    // ---------- Рация: потери не ждём ----------

    @Test
    fun liveInOrderHasNoLoss() {
        val g = LiveGapTracker()
        for (i in 0 until 5) assertEquals(0, g.accept(i))
        assertEquals(0, g.lostTail(5))
    }

    @Test
    fun liveSkipsLostChunks() {
        val g = LiveGapTracker()
        assertEquals(0, g.accept(0))
        assertEquals(2, g.accept(3)) // 1 и 2 потеряны, на них не ждём
        assertEquals(0, g.accept(4))
        assertEquals(3, g.lostTail(8)) // 5, 6, 7 не пришли
    }

    @Test
    fun liveDropsLateAndDuplicate() {
        val g = LiveGapTracker()
        g.accept(0); g.accept(3)
        assertEquals(-1, g.accept(1)) // опоздал: пропуск уже засчитан
        assertEquals(-1, g.accept(3)) // повтор
        assertEquals(0, g.accept(4))
    }

    @Test
    fun liveFirstChunkMayBeLate() {
        val g = LiveGapTracker()
        assertEquals(4, g.accept(4)) // начало рации потерялось
    }

    // ---------- Очередь отправки ----------

    private fun text(id: String) = Frame.Msg(WireMessage(id, 1L, Kind.TEXT, "hi", null))
    private fun sos(id: String) = Frame.Msg(WireMessage(id, 1L, Kind.SOS, "help", null))
    private fun voice(n: Int) = Frame.VoiceChunk("v", n, chunk(n))

    private fun drain(q: SendQueue): List<Frame> {
        val out = ArrayList<Frame>()
        while (true) out.add((q.poll() ?: break).frame!!)
        return out
    }

    @Test
    fun sosOvertakesQueuedVoice() {
        val q = SendQueue { 0L }
        q.put(Frame.VoiceStart("v", false, Protocol.VOICE_CODEC_AMR_WB, 40, 1L))
        for (i in 0 until 100) q.put(voice(i))
        q.put(Frame.VoiceEnd("v", 100))
        q.put(text("t1"))
        q.put(sos("s1"))
        val order = drain(q)
        assertEquals(sos("s1"), order[0])
        assertEquals(text("t1"), order[1])
        assertTrue(order[2] is Frame.VoiceStart)
    }

    @Test
    fun voiceKeepsItsOrder() {
        val q = SendQueue { 0L }
        q.put(Frame.VoiceStart("v", false, Protocol.VOICE_CODEC_AMR_WB, 40, 1L))
        for (i in 0 until 20) q.put(voice(i))
        q.put(Frame.VoiceEnd("v", 20))
        q.put(text("t1")) // посреди голоса
        val order = drain(q)
        val voiceOnly = order.filter { it !is Frame.Msg }
        assertTrue(voiceOnly.first() is Frame.VoiceStart)
        assertTrue(voiceOnly.last() is Frame.VoiceEnd)
        val seqs = voiceOnly.filterIsInstance<Frame.VoiceChunk>().map { it.seq }
        assertEquals((0 until 20).toList(), seqs)
    }

    @Test
    fun textAndServiceFramesKeepOrderInTheirLane() {
        val q = SendQueue { 0L }
        q.put(Frame.Hello(Protocol.VERSION, "me", "n", "r"))
        q.put(Frame.Accept)
        q.put(text("a")); q.put(text("b")); q.put(text("c"))
        q.put(Frame.Ping)
        val order = drain(q)
        assertTrue(order[0] is Frame.Hello)
        assertTrue(order[1] is Frame.Accept)
        assertTrue(order[2] is Frame.Ping) // служебный кадр обогнал текст
        assertEquals(listOf(text("a"), text("b"), text("c")), order.drop(3))
    }

    @Test
    fun expiredLiveChunksAreSkipped() {
        var now = 1000L
        val q = SendQueue { now }
        q.put(Frame.VoiceChunk("v", 0, chunk(0)), ttlMs = 500)
        q.put(Frame.VoiceChunk("v", 1, chunk(1)), ttlMs = 500)
        now = 1400
        q.put(Frame.VoiceChunk("v", 2, chunk(2)), ttlMs = 500)
        q.put(Frame.VoiceEnd("v", 3)) // конец без срока
        now = 1700 // первые два просрочены (до 1500), третий жив (до 1900)
        val order = drain(q)
        assertEquals(2, q.expired)
        assertEquals(listOf(2), order.filterIsInstance<Frame.VoiceChunk>().map { it.seq })
        assertTrue(order.last() is Frame.VoiceEnd)
    }

    @Test
    fun messageChunksNeverExpire() {
        var now = 0L
        val q = SendQueue { now }
        q.put(voice(0)) // ttl не задан
        now = 10_000_000
        assertEquals(1, drain(q).size)
        assertEquals(0, q.expired)
    }

    @Test
    fun closingComesLastAndDropsVoice() {
        val q = SendQueue { 0L }
        for (i in 0 until 10) q.put(voice(i))
        q.put(text("t"))
        q.dropVoice()
        q.putLast(Frame.Bye)
        val e1 = q.poll()!!
        assertEquals(text("t"), e1.frame)
        val e2 = q.poll()!!
        assertTrue(e2.closing)
        assertEquals(Frame.Bye, e2.frame)
        assertNull(q.poll())
    }

    @Test
    fun priorities() {
        assertEquals(SendQueue.PRIO_URGENT, SendQueue.priorityOf(sos("s")))
        assertEquals(SendQueue.PRIO_NORMAL, SendQueue.priorityOf(text("t")))
        assertEquals(SendQueue.PRIO_NORMAL, SendQueue.priorityOf(Frame.GroupLeave("g", "i")))
        assertEquals(SendQueue.PRIO_URGENT, SendQueue.priorityOf(Frame.Ack("x")))
        assertEquals(SendQueue.PRIO_VOICE, SendQueue.priorityOf(voice(0)))
        assertEquals(SendQueue.PRIO_VOICE, SendQueue.priorityOf(Frame.VoiceNack("x", listOf(1))))
    }
}
