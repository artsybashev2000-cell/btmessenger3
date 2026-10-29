package ru.btmessenger.app

import android.os.SystemClock
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Base64 без android.util.Base64 и java.util.Base64: первая не работает в тестах на компьютере,
 * вторая появилась только в Android 8 (API 26), а у нас minSdk 24.
 */
internal object Base64Lite {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun encode(b: ByteArray): String {
        val sb = StringBuilder((b.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < b.size) {
            val n = ((b[i].toInt() and 0xFF) shl 16) or ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
                .append(ALPHABET[(n shr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (b.size - i) {
            1 -> {
                val n = (b[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63]).append("==")
            }
            2 -> {
                val n = ((b[i].toInt() and 0xFF) shl 16) or ((b[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
                    .append(ALPHABET[(n shr 6) and 63]).append('=')
            }
        }
        return sb.toString()
    }

    /** null — строка не Base64 (длина не кратна 4, чужие символы, «=» не в конце). */
    fun decode(s: String): ByteArray? {
        if (s.length % 4 != 0) return null
        val pad = when {
            s.endsWith("==") -> 2
            s.endsWith("=") -> 1
            else -> 0
        }
        val out = ByteArray(s.length / 4 * 3 - pad)
        var o = 0
        var i = 0
        while (i < s.length) {
            val last = i + 4 == s.length
            val c0 = sym(s[i])
            val c1 = sym(s[i + 1])
            val c2 = if (last && pad == 2) 0 else sym(s[i + 2])
            val c3 = if (last && pad >= 1) 0 else sym(s[i + 3])
            if (c0 < 0 || c1 < 0 || c2 < 0 || c3 < 0) return null
            val n = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            out[o++] = (n shr 16).toByte()
            if (!(last && pad == 2)) out[o++] = (n shr 8).toByte()
            if (!(last && pad >= 1)) out[o++] = n.toByte()
            i += 4
        }
        return out
    }

    private fun sym(c: Char): Int = if (c.code < 128) DECODE[c.code] else -1
}

/**
 * Сборка голосового сообщения из кусков (режим «сообщение»). Куски могут прийти в любом порядке
 * и повторно; полноту проверяем по номерам 0..count-1, которые назвал отправитель в voice_end.
 */
class VoiceAssembler(
    private val maxChunks: Int = Protocol.MAX_VOICE_CHUNKS,
    private val maxBytes: Int = Protocol.MAX_VOICE_BYTES
) {
    private val parts = HashMap<Int, ByteArray>()
    var bytes = 0
        private set

    /** false — кусок не принят (номер вне лимита или превышен общий объём): сообщение надо бросить. */
    fun add(seq: Int, data: ByteArray): Boolean {
        if (seq < 0 || seq >= maxChunks) return false
        if (parts.containsKey(seq)) return true // повтор: уже есть
        if (bytes + data.size > maxBytes) return false
        parts[seq] = data
        bytes += data.size
        return true
    }

    fun isComplete(count: Int): Boolean = count in 1..maxChunks && (0 until count).all { parts.containsKey(it) }

    /** Номера недостающих кусков (не больше [limit] первых). */
    fun missing(count: Int, limit: Int = Protocol.MAX_NACK): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < count && out.size < limit) {
            if (!parts.containsKey(i)) out.add(i)
            i++
        }
        return out
    }

    /** Куски по порядку. Звать только когда [isComplete]. */
    fun chunks(count: Int): List<ByteArray> = (0 until count).map { parts.getValue(it) }
}

/**
 * Режим рации: на потерянный кусок не ждём. Кусок с номером больше ожидаемого принимаем сразу и
 * сообщаем, сколько кусков перед ним пропало (плеер подставит маскировку потери); опоздавший или
 * повторный кусок выбрасываем.
 */
class LiveGapTracker {
    /** Номер следующего ожидаемого куска. */
    var expected = 0
        private set

    /** Сколько кусков потеряно перед этим (0 — подряд), либо -1 — кусок опоздал или повторился. */
    fun accept(seq: Int): Int {
        if (seq < expected) return -1
        val lost = seq - expected
        expected = seq + 1
        return lost
    }

    /** Сколько кусков в конце так и не пришло, если всего их было [count]. */
    fun lostTail(count: Int): Int = (count - expected).coerceAtLeast(0)
}

/**
 * Очередь отправки одного соединения с приоритетами. Раньше все кадры шли строго по порядку,
 * и длинное голосовое сообщение задержало бы тревогу. Теперь тревога и служебные кадры обгоняют
 * текст, а текст обгоняет голос. Внутри одного приоритета порядок сохраняется. Обогнать можно
 * только между кадрами: уже начатый кадр дописывается (голосовой кусок — это ~130 байт).
 *
 * Кусок рации можно отправить с «сроком годности» ([put] с ttlMs): если к моменту записи в канал
 * он старше, он выбрасывается (рация не копит задержку), а номер остаётся занятым — получатель
 * увидит пропуск.
 */
class SendQueue(private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    class Entry(
        /** null только у закрывающей записи без кадра. */
        val frame: Frame?,
        val onWritten: (() -> Unit)?,
        val prio: Int,
        val seq: Long,
        val expireAt: Long,
        /** Закрывающая запись: после неё (и её кадра, если есть) соединение закрывается. */
        val closing: Boolean = false
    ) : Comparable<Entry> {
        override fun compareTo(other: Entry): Int =
            if (prio != other.prio) prio.compareTo(other.prio) else seq.compareTo(other.seq)
    }

    private val q = PriorityBlockingQueue<Entry>()
    private val seq = AtomicLong()
    private val expiredCount = AtomicInteger()

    /** Сколько кусков выброшено из-за срока годности (для отладки и тестов). */
    val expired: Int get() = expiredCount.get()

    fun put(frame: Frame, onWritten: (() -> Unit)? = null, ttlMs: Long = 0L) {
        val expireAt = if (ttlMs > 0) clock() + ttlMs else 0L
        q.put(Entry(frame, onWritten, priorityOf(frame), seq.incrementAndGet(), expireAt))
    }

    /** Закрывающая запись: уходит после всего остального. */
    fun putLast(last: Frame?) {
        q.put(Entry(last, null, PRIO_LAST, seq.incrementAndGet(), 0L, closing = true))
    }

    /** Выбросить всё голосовое (перед закрытием соединения). */
    fun dropVoice() {
        val it = q.iterator()
        while (it.hasNext()) if (it.next().prio == PRIO_VOICE) it.remove()
    }

    /** Ждёт следующую запись. */
    fun take(): Entry {
        while (true) {
            val e = q.take()
            if (!isExpired(e)) return e
        }
    }

    /** Следующая запись без ожидания, null — очередь пуста. */
    fun poll(): Entry? {
        while (true) {
            val e = q.poll() ?: return null
            if (!isExpired(e)) return e
        }
    }

    private fun isExpired(e: Entry): Boolean {
        if (e.expireAt != 0L && clock() > e.expireAt) {
            expiredCount.incrementAndGet()
            return true
        }
        return false
    }

    companion object {
        /** Служебные кадры и тревога. */
        const val PRIO_URGENT = 0

        /** Текст, геометки, группы. */
        const val PRIO_NORMAL = 1

        /** Голос: начало, куски, конец идут одной очередью и не переставляются. */
        const val PRIO_VOICE = 2
        const val PRIO_LAST = 9

        fun priorityOf(f: Frame): Int = when (f) {
            is Frame.Hello, is Frame.Accept, is Frame.Reject, is Frame.Ack,
            is Frame.Ping, is Frame.Pong, is Frame.Bye -> PRIO_URGENT
            is Frame.Msg -> if (f.message.kind == Kind.SOS) PRIO_URGENT else PRIO_NORMAL
            is Frame.VoiceStart, is Frame.VoiceChunk, is Frame.VoiceEnd, is Frame.VoiceNack -> PRIO_VOICE
            else -> PRIO_NORMAL
        }
    }
}
