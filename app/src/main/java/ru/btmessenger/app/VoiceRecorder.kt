package ru.btmessenger.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Параметры и разбор кадров AMR-WB (G.722.2) — узкополосного «телефонного» кодека для речи 16 кГц.
 *
 * Почему не Opus: Opus лучше (особенно на 8–12 кбит/с), но встроенный кодировщик Opus в MediaCodec
 * есть только с Android 10 (API 29), а у нас minSdk 24. Pure-Java порт (Concentus) возможен, но это
 * сторонняя зависимость, которую я не могу проверить без сети и сборки. AMR-WB кодировщик обязателен
 * для телефонов по требованиям Android (CDD), работает на API 24+, без зависимостей и без NDK.
 * Класс [VoiceRecorder] отдаёт просто байты, так что кодек можно заменить, не трогая сеть и интерфейс.
 */
object AmrWb {
    const val MIME = "audio/amr-wb"
    const val SAMPLE_RATE = 16_000
    const val FRAME_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000 // 320
    const val PCM_BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2 // 640, 16 бит моно

    /** 12,65 кбит/с (режим 2): заметно лучше режимов 0–1, при этом всего ~1,65 КБ/с. */
    const val BITRATE_DEFAULT = 12_650

    /** Размер кадра вместе с байтом-заголовком (TOC) по номеру режима 0..15. 0 = неизвестный режим. */
    private val FRAME_BYTES = intArrayOf(18, 24, 33, 37, 41, 47, 51, 59, 61, 6, 0, 0, 0, 0, 1, 1)

    /** Размер кадра по его первому байту (заголовку), либо 0, если режим недопустим. */
    fun frameSize(toc: Byte): Int = FRAME_BYTES[(toc.toInt() shr 3) and 0x0F]

    /**
     * Режет кусок, собранный из нескольких кадров, обратно на кадры (декодеру MediaCodec нужен
     * один кадр на входной буфер). Повреждённый или обрезанный хвост отбрасывается.
     */
    fun split(chunk: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(3)
        var pos = 0
        while (pos < chunk.size) {
            val size = frameSize(chunk[pos])
            if (size == 0 || pos + size > chunk.size) break
            out.add(chunk.copyOfRange(pos, pos + size))
            pos += size
        }
        return out
    }
}

/**
 * Запись с микрофона и сжатие в AMR-WB. Только запись и кодирование: сети и интерфейса здесь нет.
 *
 *     val rec = VoiceRecorder(this, framesPerChunk = 2, onChunk = { bytes -> ... })
 *     if (rec.start()) { ... }   // разрешение RECORD_AUDIO должно быть выдано заранее
 *     rec.stop()
 *
 * Звук 16 кГц, 16 бит, моно. Один кадр кодека = 20 мс; кусок = [framesPerChunk] кадров подряд (1..3,
 * то есть 20/40/60 мс). Кусок — это склеенные кадры с их заголовками, режет обратно [AmrWb.split].
 * [onChunk] вызывается из потока записи, не из главного: из него нельзя трогать интерфейс.
 * Не держите в [onChunk] долгих операций: пока он работает, микрофон не читается.
 */
class VoiceRecorder(
    context: Context,
    private val framesPerChunk: Int = 2,
    private val bitrate: Int = AmrWb.BITRATE_DEFAULT,
    private val onChunk: (ByteArray) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) {
    private val appContext = context.applicationContext

    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null

    /** Длительность одного куска, мс. */
    val chunkMs: Int get() = framesPerChunk * AmrWb.FRAME_MS

    val isRecording: Boolean get() = running

    init {
        require(framesPerChunk in 1..3) { "framesPerChunk должен быть от 1 до 3 (20–60 мс)" }
    }

    /**
     * Запускает запись. Возвращает false, если запустить не вышло (нет разрешения, микрофон занят,
     * на телефоне нет кодировщика); причина уходит в [onError]. Повторный вызов во время записи
     * ничего не делает и возвращает true.
     */
    @Synchronized
    @SuppressLint("MissingPermission") // разрешение проверяется ниже
    fun start(): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError(SecurityException("Нет разрешения RECORD_AUDIO"))
            return false
        }
        try {
            val rec = createRecord()
            val enc = createEncoder()
            record = rec
            codec = enc
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Микрофон занят другим приложением")
            }
        } catch (t: Throwable) {
            release()
            onError(t)
            return false
        }
        running = true
        thread = Thread({ loop() }, "VoiceRecorder").also { it.start() }
        return true
    }

    /**
     * Останавливает запись. Последний неполный кусок досылается через [onChunk] до возврата.
     * Ждёт завершения потока недолго (до секунды), поэтому звать можно и из главного потока.
     */
    fun stop() {
        val t: Thread?
        synchronized(this) {
            running = false
            t = thread
            thread = null
        }
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            AmrWb.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) throw IllegalStateException("Телефон не поддерживает запись 16 кГц")
        // запас на 8 кадров (160 мс): если поток замешкается, звук не потеряется
        val bufSize = maxOf(minBuf, AmrWb.PCM_BYTES_PER_FRAME * 8)
        // VOICE_COMMUNICATION включает эхо- и шумоподавление, для рации это полезно; не вышло — обычный микрофон
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
            var r: AudioRecord? = null
            try {
                r = AudioRecord(
                    source, AmrWb.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize
                )
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
            } catch (e: IllegalArgumentException) {
                // источник не подошёл, пробуем следующий
            }
            r?.release()
        }
        throw IllegalStateException("Не удалось открыть микрофон")
    }

    private fun createEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(AmrWb.MIME, AmrWb.SAMPLE_RATE, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        val enc = try {
            MediaCodec.createEncoderByType(AmrWb.MIME)
        } catch (e: Exception) {
            throw UnsupportedOperationException("На телефоне нет кодировщика AMR-WB", e)
        }
        try {
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
        } catch (t: Throwable) {
            enc.release()
            throw t
        }
        return enc
    }

    // ---------- Поток записи ----------

    private fun loop() {
        val rec = record ?: return
        val enc = codec ?: return
        val pcm = ByteArray(AmrWb.PCM_BYTES_PER_FRAME)
        val info = MediaCodec.BufferInfo()
        val chunk = ChunkBuilder()
        var ptsUs = 0L
        try {
            while (running) {
                if (!readFully(rec, pcm)) throw IllegalStateException("Ошибка чтения с микрофона")
                val idx = enc.dequeueInputBuffer(20_000)
                if (idx >= 0) {
                    val buf = enc.getInputBuffer(idx)!!
                    buf.clear()
                    buf.put(pcm)
                    enc.queueInputBuffer(idx, 0, pcm.size, ptsUs, 0)
                    ptsUs += AmrWb.FRAME_MS * 1000L
                } // иначе кодек не успевает — кадр пропускаем, запись не стопорим
                drain(enc, info, chunk, endOfStream = false)
            }
            finish(enc, info, chunk, ptsUs)
        } catch (t: Throwable) {
            Log.w(TAG, "Запись остановлена из-за ошибки", t)
            running = false
            try {
                onError(t)
            } catch (e: Throwable) {
                Log.w(TAG, "onError бросил исключение", e)
            }
        } finally {
            synchronized(this) { release() }
        }
    }

    /** Читает ровно столько байт, сколько влезает в [dst]; false — ошибка (отрицательный код). */
    private fun readFully(rec: AudioRecord, dst: ByteArray): Boolean {
        var off = 0
        while (off < dst.size) {
            if (!running) return true // остановили посреди кадра: неполный кадр не кодируем
            val n = rec.read(dst, off, dst.size - off)
            if (n < 0) return false
            off += n
        }
        return off == dst.size
    }

    /** Забирает готовые кадры кодека (без ожидания). Возвращает true, когда встретился конец потока. */
    private fun drain(enc: MediaCodec, info: MediaCodec.BufferInfo, chunk: ChunkBuilder, endOfStream: Boolean): Boolean {
        val timeoutUs = if (endOfStream) 10_000L else 0L
        while (true) {
            val i = enc.dequeueOutputBuffer(info, timeoutUs)
            when {
                i >= 0 -> {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        val out = enc.getOutputBuffer(i)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val frame = ByteArray(info.size)
                        out.get(frame)
                        chunk.add(frame)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    enc.releaseOutputBuffer(i, false)
                    if (eos) return true
                }
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                // INFO_OUTPUT_FORMAT_CHANGED и прочее: данных нет, читаем дальше
            }
        }
    }

    /** Закрывает поток кодека и досылает остаток. */
    private fun finish(enc: MediaCodec, info: MediaCodec.BufferInfo, chunk: ChunkBuilder, ptsUs: Long) {
        val idx = enc.dequeueInputBuffer(50_000)
        if (idx >= 0) {
            enc.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            var tries = 0
            while (tries++ < 20 && !drain(enc, info, chunk, endOfStream = true)) { /* ждём конец потока */ }
        }
        chunk.flush()
    }

    /** Собирает кадры в куски по [framesPerChunk] штук. */
    private inner class ChunkBuilder {
        private var buf = ByteArray(0)
        private var frames = 0

        fun add(frame: ByteArray) {
            buf += frame
            if (++frames >= framesPerChunk) flush()
        }

        fun flush() {
            if (buf.isEmpty()) return
            val ready = buf
            buf = ByteArray(0)
            frames = 0
            try {
                onChunk(ready)
            } catch (t: Throwable) {
                Log.w(TAG, "onChunk бросил исключение", t)
            }
        }
    }

    private fun release() {
        try { record?.stop() } catch (e: Exception) { }
        try { record?.release() } catch (e: Exception) { }
        try { codec?.stop() } catch (e: Exception) { }
        try { codec?.release() } catch (e: Exception) { }
        record = null
        codec = null
        running = false
    }

    private companion object {
        const val TAG = "VoiceRecorder"
    }
}
