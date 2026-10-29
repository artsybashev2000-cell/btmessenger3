package ru.btmessenger.app

import android.content.Context
import java.io.File

/**
 * Хранение звука голосовых сообщений. Звук лежит в файлах (filesDir/voice/<id>.awb), а не в SQLite:
 * сообщение до 60 с весит около 100 КБ. В базе у такого сообщения kind = voice, а в text записана
 * длина звука в миллисекундах (см. [voiceMs]).
 *
 * Формат файла: подряд кадры AMR-WB вместе с заголовками, без обёртки. Кадры самоописываемые
 * (размер берётся из заголовка), поэтому [AmrWb.split] режет весь файл целиком.
 */
object VoiceStore {
    private const val DIR = "voice"

    /** Кадров в куске при повторной отправке из файла (границы кусков в файле не хранятся): 2 кадра = 40 мс. */
    const val RESEND_FRAMES_PER_CHUNK = 2

    /** Id приходит от собеседника, поэтому в имя файла он попадает только из безопасных символов (без «..» и «/»). */
    fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 && id.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '-' || it == '_' }

    /** Сколько миллисекунд звука в [chunks] (по числу корректных кадров). */
    fun durationMs(chunks: List<ByteArray>): Int = chunks.sumOf { AmrWb.split(it).size } * AmrWb.FRAME_MS

    /** Собирает кадры в куски по [perChunk] штук (для повторной отправки сообщения из файла). */
    fun regroup(frames: List<ByteArray>, perChunk: Int = RESEND_FRAMES_PER_CHUNK): List<ByteArray> {
        val n = perChunk.coerceAtLeast(1)
        val out = ArrayList<ByteArray>((frames.size + n - 1) / n)
        var i = 0
        while (i < frames.size) {
            val end = minOf(i + n, frames.size)
            var size = 0
            for (k in i until end) size += frames[k].size
            val chunk = ByteArray(size)
            var pos = 0
            for (k in i until end) {
                System.arraycopy(frames[k], 0, chunk, pos, frames[k].size)
                pos += frames[k].size
            }
            out.add(chunk)
            i = end
        }
        return out
    }

    private fun file(ctx: Context, id: String): File? {
        if (!isSafeId(id)) return null
        return File(File(ctx.applicationContext.filesDir, DIR), "$id.awb")
    }

    /** Записать звук сообщения. false — не вышло (нет места, негодный id). Пишет сначала во временный файл. */
    fun save(ctx: Context, id: String, chunks: List<ByteArray>): Boolean {
        val f = file(ctx, id) ?: return false
        return try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$id.tmp")
            tmp.outputStream().use { o -> for (c in chunks) o.write(c) }
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            false
        }
    }

    /** Кадры сообщения по порядку; null — файла нет или он пуст. Читать не с главного потока. */
    fun frames(ctx: Context, id: String): List<ByteArray>? {
        val f = file(ctx, id) ?: return null
        return try {
            if (!f.isFile) return null
            val frames = AmrWb.split(f.readBytes())
            if (frames.isEmpty()) null else frames
        } catch (e: Exception) {
            null
        }
    }

    /** Звук для повторной отправки: кадры, собранные обратно в куски по 40 мс. */
    fun chunksForResend(ctx: Context, id: String): List<ByteArray>? = frames(ctx, id)?.let { regroup(it) }

    fun delete(ctx: Context, id: String) {
        try {
            file(ctx, id)?.delete()
        } catch (e: Exception) {
        }
    }
}

/** Длина голосового сообщения в миллисекундах (в базе лежит в тексте сообщения); 0, если не число. */
val ChatMessage.voiceMs: Int get() = text.toIntOrNull()?.coerceAtLeast(0) ?: 0
