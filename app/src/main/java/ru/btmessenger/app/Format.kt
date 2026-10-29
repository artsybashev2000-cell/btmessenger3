package ru.btmessenger.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Форматирование для интерфейса и уведомлений. */
object Format {
    /** Точка старше этого на момент отправки помечается как устаревшая. */
    const val STALE_FIX_MS = 2 * 60_000L

    fun coords(g: GeoPoint): String = String.format(Locale.US, "%.5f, %.5f", g.lat, g.lon)

    fun time(ms: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "dd.MM HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
    }

    fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    /** «45 с», «12 мин», «2 ч 5 мин», «3 дн». */
    fun duration(ctx: Context, ms: Long): String {
        val s = (abs(ms) / 1000).coerceAtLeast(1)
        return when {
            s < 60 -> ctx.getString(R.string.dur_sec, s)
            s < 3600 -> ctx.getString(R.string.dur_min, s / 60)
            s < 86_400 -> if ((s % 3600) / 60 == 0L) ctx.getString(R.string.dur_hour, s / 3600)
            else ctx.getString(R.string.dur_hour_min, s / 3600, (s % 3600) / 60)
            else -> ctx.getString(R.string.dur_day, s / 86_400)
        }
    }

    /** «0:07», «1:05»: длина голосового сообщения (округляется до секунд). */
    fun mmss(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 500) / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    fun distance(ctx: Context, meters: Float): String = when {
        meters < 10f -> ctx.getString(R.string.dist_m, meters.roundToInt())
        meters < 1000f -> ctx.getString(R.string.dist_m, ((meters / 5f).roundToInt() * 5))
        meters < 10_000f -> ctx.getString(R.string.dist_km, String.format(Locale.getDefault(), "%.1f", meters / 1000f))
        else -> ctx.getString(R.string.dist_km, (meters / 1000f).roundToLong().toString())
    }

    fun accuracy(ctx: Context, acc: Float?): String? =
        acc?.let { ctx.getString(R.string.geo_accuracy, it.roundToInt().coerceAtLeast(1)) }

    /** «±12 м», а если точность неизвестна, так и пишем: пустое место выглядело бы как «точно». */
    fun accuracyOrUnknown(ctx: Context, acc: Float?): String =
        accuracy(ctx, acc) ?: ctx.getString(R.string.geo_accuracy_unknown)

    /** Сколько точке было лет в момент отправки (по часам отправителя — без расхождения часов). */
    fun fixAgeAtSend(m: ChatMessage): Long = m.geo?.let { (m.sentAt - it.fixTime).coerceAtLeast(0) } ?: 0

    /** Строка под координатами: точность и время замера; предупреждение, если точка старая. */
    fun geoMeta(ctx: Context, m: ChatMessage): String {
        val g = m.geo ?: return ""
        val parts = mutableListOf<String>()
        parts += accuracyOrUnknown(ctx, g.accuracy)
        parts += ctx.getString(R.string.geo_fix_at, clock(g.fixTime))
        val line = parts.joinToString(" · ")
        val age = fixAgeAtSend(m)
        return if (age > STALE_FIX_MS) line + "\n" + ctx.getString(R.string.geo_stale, duration(ctx, age)) else line
    }

    fun sosSummary(ctx: Context, m: ChatMessage): String {
        val head = if (m.text.isNotBlank()) m.text + "\n" else ""
        val g = m.geo ?: return head + ctx.getString(R.string.sos_no_geo)
        return head + "📍 " + coords(g) + "\n" + geoMeta(ctx, m)
    }

    fun peerDescription(ctx: Context, p: BtLink.PeerInfo): String {
        val sb = StringBuilder(p.title)
        if (p.role.isNotBlank()) sb.append(" (").append(p.role).append(')')
        if (p.name.isNotBlank() && p.deviceName.isNotBlank()) {
            sb.append('\n').append(ctx.getString(R.string.request_device, p.deviceName))
        }
        return sb.toString()
    }

    fun preview(ctx: Context, kind: Kind?, text: String?, fromMe: Boolean): String {
        val body = when (kind) {
            Kind.GEO -> ctx.getString(R.string.preview_geo)
            Kind.SOS -> ctx.getString(R.string.preview_sos)
            Kind.VOICE -> ctx.getString(R.string.preview_voice, mmss(text?.toLongOrNull() ?: 0L))
            else -> text.orEmpty().replace('\n', ' ')
        }
        return if (fromMe) ctx.getString(R.string.preview_you, body) else body
    }

    /** Уровень сигнала по RSSI — очень грубо, расстояние по нему не определить точно. */
    fun signal(ctx: Context, rssi: Int): String {
        val level = when {
            rssi >= -60 -> R.string.signal_excellent
            rssi >= -75 -> R.string.signal_good
            rssi >= -85 -> R.string.signal_weak
            else -> R.string.signal_very_weak
        }
        return ctx.getString(R.string.signal_fmt, ctx.getString(level), rssi)
    }

    /** Румб по азимуту: С, СВ, В, ЮВ, Ю, ЮЗ, З, СЗ. */
    fun compassPoint(ctx: Context, degrees: Float): String {
        val names = ctx.resources.getStringArray(R.array.compass_points)
        val idx = (((degrees % 360f + 360f) % 360f + 22.5f) / 45f).toInt() % 8
        return names[idx]
    }
}
