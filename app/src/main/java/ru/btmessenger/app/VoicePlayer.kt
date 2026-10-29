package ru.btmessenger.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Декодер AMR-WB: кадр на входе, звук PCM (16 бит, 16 кГц, моно) на выходе. Встроенный MediaCodec,
 * поэтому работает с API 24 без зависимостей (как и кодировщик в [VoiceRecorder]).
 * Только для одного потока: создавать, вызывать и закрывать в одном и том же.
 */
internal class AmrWbDecoder {
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    init {
        val format = MediaFormat.createAudioFormat(AmrWb.MIME, AmrWb.SAMPLE_RATE, 1)
        val dec = try {
            MediaCodec.createDecoderByType(AmrWb.MIME)
        } catch (e: Exception) {
            throw UnsupportedOperationException("На телефоне нет декодера AMR-WB", e)
        }
        try {
            dec.configure(format, null, null, 0)
            dec.start()
        } catch (t: Throwable) {
            dec.release()
            throw t
        }
        codec = dec
    }

    /** Отдаёт один кадр (с заголовком) декодеру; готовый звук сразу уходит в [sink]. */
    fun decode(frame: ByteArray, sink: (ByteArray) -> Unit) {
        var fed = false
        var tries = 0
        while (!fed && tries++ < 50) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                buf.put(frame)
                codec.queueInputBuffer(idx, 0, frame.size, ptsUs, 0)
                ptsUs += AmrWb.FRAME_MS * 1000L
                fed = true
            } else {
                drain(sink, 0L) // входные буферы заняты: освобождаем выходные
            }
        }
        drain(sink, 5_000L)
    }

    /** Конец потока: дожидается остатка звука. */
    fun finish(sink: (ByteArray) -> Unit) {
        val idx = codec.dequeueInputBuffer(50_000)
        if (idx < 0) return
        codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        var tries = 0
        while (tries++ < 20 && !drain(sink, 10_000L)) { /* ждём конец потока */ }
    }

    /** Забирает готовый звук. Возвращает true, когда встретился конец потока. */
    private fun drain(sink: (ByteArray) -> Unit, firstTimeoutUs: Long): Boolean {
        var timeout = firstTimeoutUs
        while (true) {
            val i = codec.dequeueOutputBuffer(info, timeout)
            when {
                i >= 0 -> {
                    timeout = 0L
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(i)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size)
                        out.get(pcm)
                        sink(pcm)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(i, false)
                    if (eos) return true
                }
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                // INFO_OUTPUT_FORMAT_CHANGED и прочее: данных нет, читаем дальше
            }
        }
    }

    fun release() {
        try { codec.stop() } catch (e: Exception) { }
        try { codec.release() } catch (e: Exception) { }
    }
}

/**
 * Вывод звука PCM (16 бит, 16 кГц, моно) в динамик. Громкость и маршрут — по умолчанию: звук идёт
 * как обычное медиа (громкость кнопками громкости, динамик телефона или наушники, если подключены),
 * поэтому рация слышна без поднесения телефона к уху. Писать можно из одного потока, [abort] — из любого.
 */
internal class PcmOut {
    private val track: AudioTrack
    @Volatile private var aborted = false
    private var writtenBytes = 0L
    private var started = false

    init {
        val min = AudioTrack.getMinBufferSize(
            AmrWb.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // запас 400 мс: короткие паузы в приёме звук не рвут
        val size = maxOf(if (min > 0) min else 0, AmrWb.PCM_BYTES_PER_FRAME * 20)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AmrWb.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("Не удалось открыть динамик")
        }
    }

    /** Записать звук (ждёт, пока в буфере появится место). */
    fun write(pcm: ByteArray) {
        if (aborted) return
        var off = 0
        var idle = 0
        while (off < pcm.size && !aborted) {
            if (!started) {
                started = true
                track.play()
            }
            val n = track.write(pcm, off, pcm.size - off)
            if (n < 0) {
                if (aborted) return
                throw IllegalStateException("Ошибка вывода звука: $n")
            }
            if (n == 0) {
                if (++idle > 200) throw IllegalStateException("Динамик не принимает звук")
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            } else {
                idle = 0
                off += n
                writtenBytes += n
            }
        }
    }

    /** Тишина длиной [ms] (на месте потерянных кусков рации). */
    fun writeSilence(ms: Int) {
        if (ms <= 0) return
        write(ByteArray(AmrWb.SAMPLE_RATE / 1000 * 2 * ms)) // 16 отсчётов в мс, по 2 байта
    }

    /** Сколько миллисекунд звука реально проиграно (по положению головки AudioTrack). */
    fun playedMs(): Int {
        if (!started) return 0
        val frames = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return (frames * 1000L / AmrWb.SAMPLE_RATE).toInt()
    }

    /** Ждёт, пока записанное доиграет (но не дольше [maxWaitMs]). */
    fun waitPlayed(maxWaitMs: Long = 1_500L) {
        if (!started || aborted) return
        val total = writtenBytes / 2
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (!aborted && System.currentTimeMillis() < deadline) {
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (head >= total) break
            try {
                Thread.sleep(20)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** Оборвать вывод сразу; поток, ждущий в [write], просыпается. Из любого потока. */
    fun abort() {
        aborted = true
        try { track.pause() } catch (e: Exception) { }
        try { track.flush() } catch (e: Exception) { }
    }

    fun release() {
        aborted = true
        try { track.stop() } catch (e: Exception) { }
        try { track.release() } catch (e: Exception) { }
    }
}

/** Просит у системы звук на время передачи: чужая музыка при этом тише. Звонок и другие приложения не трогаем. */
internal class AudioFocus(ctx: Context) {
    private val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val listener = AudioManager.OnAudioFocusChangeListener { }
    private var held = false

    @Suppress("DEPRECATION")
    fun request() {
        if (held) return
        held = try {
            am?.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    fun abandon() {
        if (!held) return
        held = false
        try {
            am?.abandonAudioFocus(listener)
        } catch (e: Exception) {
        }
    }
}

/**
 * Проигрывание голосовых сообщений из ленты. Одновременно играет одно: запуск другого останавливает
 * текущее. [onChange] вызывается на главном потоке: (id играющего или null, позиция в мс). Вызывать
 * [play], [stop] и [release] нужно с главного потока.
 */
class VoicePlayer(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Что играет и где: null — ничего. */
    var onChange: ((id: String?, posMs: Int) -> Unit)? = null

    private class Session(val id: String) {
        @Volatile var cancelled = false
        @Volatile var out: PcmOut? = null
    }

    private var current: Session? = null

    val playingId: String? get() = current?.id

    private val ticker = object : Runnable {
        override fun run() {
            val s = current ?: return
            val pos = s.out?.playedMs() ?: 0
            onChange?.invoke(s.id, pos)
            main.postDelayed(this, TICK_MS)
        }
    }

    /** Играть [frames] (кадры AMR-WB по порядку) как сообщение [id]. */
    fun play(id: String, frames: List<ByteArray>) {
        stop()
        if (frames.isEmpty()) return
        val s = Session(id)
        current = s
        onChange?.invoke(id, 0)
        main.postDelayed(ticker, TICK_MS)
        Thread({ run(s, frames) }, "VoicePlayer").start()
    }

    fun stop() {
        val s = current ?: return
        current = null
        s.cancelled = true
        s.out?.abort()
        main.removeCallbacks(ticker)
        onChange?.invoke(null, 0)
    }

    fun release() {
        onChange = null
        stop()
    }

    private fun run(s: Session, frames: List<ByteArray>) {
        var out: PcmOut? = null
        var dec: AmrWbDecoder? = null
        val focus = AudioFocus(appContext)
        var failure: Throwable? = null
        try {
            val o = PcmOut()
            out = o
            s.out = o
            val d = AmrWbDecoder()
            dec = d
            focus.request()
            val sink = { pcm: ByteArray -> o.write(pcm) }
            for (f in frames) {
                if (s.cancelled) break
                d.decode(f, sink)
            }
            if (!s.cancelled) {
                d.finish(sink)
                o.waitPlayed()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось проиграть голосовое сообщение", t)
            failure = t
        } finally {
            focus.abandon()
            dec?.release()
            out?.release()
        }
        val error = failure
        main.post {
            if (current === s) {
                current = null
                main.removeCallbacks(ticker)
                onChange?.invoke(null, 0)
                if (error != null) onError?.invoke(error)
            }
        }
    }

    /** Звук не проигрался (нет декодера, динамик занят). Главный поток. */
    var onError: ((Throwable) -> Unit)? = null

    private companion object {
        const val TAG = "VoicePlayer"
        const val TICK_MS = 100L
    }
}

/**
 * Проигрывание входящей рации: звук идёт в динамик сразу по мере прихода, без нажатия «воспроизвести».
 * Живёт в [App] и управляется из [LinkService], поэтому рация слышна и при выключенном экране.
 * Все методы не блокируют и безопасны из любого потока.
 *
 * Небольшой буфер перед стартом (200 мс) сглаживает неровный приход кусков по Bluetooth. Если
 * куски накопились больше чем на 600 мс (связь «пачками»), старые выбрасываются: в рации важнее
 * свежесть, чем полнота. На месте потерянных кусков ставится тишина.
 */
class LivePlayer(context: Context) {
    private val appContext = context.applicationContext

    private sealed class Item {
        class Chunk(val lostBefore: Int, val data: ByteArray) : Item()
        object End : Item()
    }

    private class Session(val id: String, val chunkMs: Int) {
        val queue = LinkedBlockingQueue<Item>()
        @Volatile var stopped = false
        @Volatile var out: PcmOut? = null
        @Volatile var thread: Thread? = null
    }

    private val lock = Any()
    private var session: Session? = null

    /** Собеседник начал рацию [id]; прежняя рация (если играла) обрывается. */
    fun begin(id: String, chunkMs: Int) {
        val s = Session(id, chunkMs.coerceIn(10, 200))
        synchronized(lock) {
            stopLocked()
            session = s
        }
        val t = Thread({ run(s) }, "LivePlayer")
        s.thread = t
        t.start()
    }

    /** Очередной кусок рации [id]; чужой или уже закончившейся рации — игнорируем. */
    fun chunk(id: String, lostBefore: Int, data: ByteArray) {
        val s = synchronized(lock) { session?.takeIf { it.id == id } } ?: return
        s.queue.put(Item.Chunk(lostBefore.coerceIn(0, MAX_LOST_CHUNKS), data))
    }

    /** Рация закончилась: доигрывает то, что принято, и замолкает. */
    fun end(id: String) {
        val s = synchronized(lock) { session?.takeIf { it.id == id } } ?: return
        s.queue.put(Item.End)
    }

    /** Оборвать немедленно (мы сами начали говорить, служба остановилась). */
    fun stopAll() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        val s = session ?: return
        session = null
        s.stopped = true
        s.out?.abort()
        s.thread?.interrupt()
    }

    private fun run(s: Session) {
        var out: PcmOut? = null
        var dec: AmrWbDecoder? = null
        val focus = AudioFocus(appContext)
        try {
            // ждём первый кусок и копим стартовый запас
            var first: Item? = s.queue.poll(FIRST_WAIT_MS, TimeUnit.MILLISECONDS)
            if (first == null || s.stopped) return
            Thread.sleep(PREBUFFER_MS)
            val o = PcmOut()
            out = o
            s.out = o
            val d = AmrWbDecoder()
            dec = d
            focus.request()
            val sink = { pcm: ByteArray -> o.write(pcm) }
            val maxBacklog = (MAX_BACKLOG_MS / s.chunkMs).coerceAtLeast(4)
            while (!s.stopped) {
                val item = first ?: s.queue.poll(IDLE_WAIT_MS, TimeUnit.MILLISECONDS) ?: break // тишина: конец потеряли
                first = null
                if (item is Item.End) break
                if (item is Item.Chunk) {
                    // накопилось слишком много: пропускаем старые куски, чтобы не отставать от говорящего
                    var cur: Item.Chunk = item
                    while (s.queue.size > maxBacklog) {
                        val next = s.queue.poll() ?: break
                        if (next is Item.Chunk) {
                            cur = next
                        } else {
                            s.queue.put(next) // конец передачи остаётся в очереди
                            break
                        }
                    }
                    if (cur.lostBefore > 0) o.writeSilence(cur.lostBefore * s.chunkMs)
                    for (frame in AmrWb.split(cur.data)) {
                        if (s.stopped) break
                        d.decode(frame, sink)
                    }
                }
            }
            if (!s.stopped) {
                d.finish(sink)
                o.waitPlayed()
            }
        } catch (e: InterruptedException) {
            // остановили
        } catch (t: Throwable) {
            Log.w(TAG, "Рация не проигралась", t)
        } finally {
            focus.abandon()
            dec?.release()
            out?.release()
            synchronized(lock) { if (session === s) session = null }
        }
    }

    private companion object {
        const val TAG = "LivePlayer"
        const val FIRST_WAIT_MS = 2_000L
        const val PREBUFFER_MS = 200L
        const val IDLE_WAIT_MS = 4_000L
        const val MAX_BACKLOG_MS = 600
        const val MAX_LOST_CHUNKS = 50
    }
}
