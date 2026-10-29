package ru.btmessenger.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Насколько телефон «прощает» малые движения. Чем выше чувствительность, тем меньшее движение
 * сбрасывает отсчёт неподвижности: тревог меньше, но человек с дрожью в руке может «не замереть» никогда.
 *
 * stdLimit — разброс модуля ускорения за окно 5 с (м/с²), tiltDeg — на сколько градусов должна повернуться
 * линия силы тяжести от начала неподвижности, чтобы это считалось движением. Числа оценочные (см. B5.1_изменения.md);
 * их нужно сверить на реальных телефонах по [StillnessDetector.lastStd].
 */
enum class StillSensitivity(val stdLimit: Double, val tiltDeg: Double) {
    LOW(0.30, 10.0),
    MEDIUM(0.15, 6.0),
    HIGH(0.06, 3.0);

    companion object {
        fun of(level: Int): StillSensitivity = values()[level.coerceIn(0, values().size - 1)]
    }
}

/** Что, по косвенным признакам, происходит с телефоном, пока он неподвижен. */
enum class StillKind {
    /** Лежит ровно и «мёртво» тихо: почти наверняка на столе или полу. */
    ON_SURFACE,

    /** Есть едва заметная живая дрожь: телефон, вероятно, в руке или на теле. */
    CARRIED,

    /** Тих, но не лежит ровно и дрожи нет (карман, рюкзак, прислонён): понять нельзя. */
    UNKNOWN
}

/** Настройки определения неподвижности и их значения по умолчанию. */
object StillnessConfig {
    /** 2 минуты: достаточно, чтобы не срабатывать на паузу в движении, и не слишком долго при травме (см. B5.1_изменения.md). */
    const val DEFAULT_SECONDS = 120
    const val MIN_SECONDS = 30
    const val MAX_SECONDS = 1800
    const val DEFAULT_SENSITIVITY = 1 // StillSensitivity.MEDIUM
    const val DEFAULT_IGNORE_SURFACE = true

    /** Время на ответ «Я в порядке» после предупреждения. */
    const val DEFAULT_REPLY_SECONDS = 60
    const val MIN_REPLY_SECONDS = 15
    const val MAX_REPLY_SECONDS = 300

    // Внутренние константы алгоритма
    const val WINDOW_NS = 5_000_000_000L
    const val GAP_NS = 10_000_000_000L
    const val MIN_SAMPLES = 10
    const val SURFACE_STD = 0.02
    const val FLAT_RATIO = 0.96

    /** 5 Гц: хватает для окна 5 с (25 отсчётов) и почти не нагружает датчик. */
    const val SAMPLING_US = 200_000

    /** Копим отсчёты в буфере датчика до 10 с: процессору не нужно просыпаться на каждый. */
    const val MAX_LATENCY_US = 10_000_000
}

/**
 * Логика определения неподвижности без Android: принимает отсчёты акселерометра, ничего не знает о датчиках.
 * Поэтому её можно проверить обычным тестом.
 *
 * Время идёт по меткам отсчётов (event.timestamp, наносекунды), а не по часам телефона: при пакетной доставке
 * отсчёты приходят пачками, но метки остаются настоящими.
 */
class StillnessLogic(
    var thresholdSec: Int = StillnessConfig.DEFAULT_SECONDS,
    var sensitivity: StillSensitivity = StillSensitivity.of(StillnessConfig.DEFAULT_SENSITIVITY),
    var ignoreSurface: Boolean = StillnessConfig.DEFAULT_IGNORE_SURFACE,
    /** Неподвижность дошла до порога. [seconds] — сколько секунд телефон уже не двигался. */
    var onStill: (seconds: Int, kind: StillKind) -> Unit = { _, _ -> },
    /** Движение после того, как onStill уже сработал (чтобы отменить вопрос пользователю). */
    var onMoved: () -> Unit = {}
) {
    // окно
    private var winStart = -1L
    private var lastT = -1L
    private var n = 0
    private var mean = 0.0 // Welford: среднее и сумма квадратов отклонений модуля
    private var m2 = 0.0
    private var sx = 0.0
    private var sy = 0.0
    private var sz = 0.0

    // период неподвижности
    private var stillSince = -1L
    private var anchor: DoubleArray? = null
    private var fired = false
    private var allFlat = true
    private var anyTremor = false

    /** Разброс модуля ускорения в последнем закрытом окне, м/с²: для подбора порогов на реальных телефонах. */
    var lastStd = 0.0
        private set

    /** Оценка, что за телефон по последнему окну неподвижности (до начала неподвижности — UNKNOWN). */
    var kind = StillKind.UNKNOWN
        private set

    /** Сколько секунд длится текущая неподвижность на момент последнего окна (0 — сейчас движение). */
    var stillSeconds = 0
        private set

    fun reset() {
        resetWindow()
        endPeriod(notify = false)
        lastT = -1L
    }

    /** После отказа пользователя: начать отсчёт заново, как будто он только что пошевелился. */
    fun rearm() {
        endPeriod(notify = false)
    }

    fun onSample(tNanos: Long, x: Double, y: Double, z: Double) {
        if (lastT >= 0 && tNanos - lastT > StillnessConfig.GAP_NS) {
            // датчик молчал: что было в это время — неизвестно, неподвижность подтвердить нельзя
            resetWindow()
            endPeriod(notify = false)
        }
        lastT = tNanos
        if (winStart < 0) winStart = tNanos
        if (tNanos - winStart >= StillnessConfig.WINDOW_NS) {
            closeWindow(tNanos)
            resetWindow()
            winStart = tNanos
        }
        val m = sqrt(x * x + y * y + z * z)
        n++
        val d = m - mean
        mean += d / n
        m2 += d * (m - mean)
        sx += x
        sy += y
        sz += z
    }

    private fun resetWindow() {
        winStart = -1L
        n = 0
        mean = 0.0
        m2 = 0.0
        sx = 0.0
        sy = 0.0
        sz = 0.0
    }

    private fun endPeriod(notify: Boolean) {
        val wasFired = fired
        stillSince = -1L
        anchor = null
        fired = false
        allFlat = true
        anyTremor = false
        stillSeconds = 0
        kind = StillKind.UNKNOWN
        if (notify && wasFired) onMoved()
    }

    private fun closeWindow(end: Long) {
        if (n < StillnessConfig.MIN_SAMPLES) {
            endPeriod(notify = false) // слишком мало отсчётов, судить нельзя
            return
        }
        val std = sqrt(m2 / n)
        lastStd = std
        val avg = doubleArrayOf(sx / n, sy / n, sz / n)
        val a = anchor
        val tilt = if (a == null) 0.0 else angleDeg(a, avg)
        if (std >= sensitivity.stdLimit || tilt >= sensitivity.tiltDeg) {
            endPeriod(notify = true)
            return
        }
        // тихое окно
        if (stillSince < 0) {
            stillSince = winStart
            anchor = avg
        }
        val norm = sqrt(avg[0] * avg[0] + avg[1] * avg[1] + avg[2] * avg[2])
        val flat = norm > 1.0 && abs(avg[2]) / norm >= StillnessConfig.FLAT_RATIO
        if (!flat) allFlat = false
        if (std >= StillnessConfig.SURFACE_STD) anyTremor = true
        kind = when {
            anyTremor -> StillKind.CARRIED
            allFlat -> StillKind.ON_SURFACE
            else -> StillKind.UNKNOWN
        }
        val elapsed = ((end - stillSince) / 1_000_000_000L).toInt()
        stillSeconds = elapsed
        if (!fired && elapsed >= thresholdSec) {
            if (kind == StillKind.ON_SURFACE && ignoreSurface) return // на столе: ждём, вдруг его подхватят и положат иначе
            fired = true
            onStill(elapsed, kind)
        }
    }

    private fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na < 1e-6 || nb < 1e-6) return 0.0
        val c = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }
}

/**
 * Датчик «человек не двигается»: следит за акселерометром и сообщает, что телефон неподвижен заданное время.
 *
 * Не запускается, пока пользователь не включил функцию ([Prefs.stillEnabled]): [start] без этого ничего не делает.
 * Обратные вызовы приходят в главном потоке.
 */
class StillnessDetector(private val ctx: Context, private val prefs: Prefs) {

    interface Listener {
        /** Телефон неподвижен уже [seconds] секунд. Вызывается один раз за период неподвижности. */
        fun onStill(seconds: Int)

        /** Движение после [onStill]: вопрос пользователю можно снять. */
        fun onMoved() {}
    }

    var listener: Listener? = null

    private val handler = Handler(Looper.getMainLooper())
    private val logic = StillnessLogic(
        onStill = { seconds, _ -> listener?.onStill(seconds) },
        onMoved = { listener?.onMoved() }
    )
    private var manager: SensorManager? = null
    private var sensorListener: SensorEventListener? = null

    val isRunning: Boolean get() = sensorListener != null

    /** Последняя оценка, что за телефон (на столе / в руке / неясно). */
    val kind: StillKind get() = logic.kind

    /** Для отладки и подбора порогов: разброс ускорения в последнем окне, м/с². */
    val lastStd: Double get() = logic.lastStd

    /** Прочитать настройки заново (после изменения в настройках); отсчёт неподвижности начинается сначала. */
    fun reconfigure() {
        logic.thresholdSec = prefs.stillSeconds
        logic.sensitivity = StillSensitivity.of(prefs.stillSensitivity)
        logic.ignoreSurface = prefs.stillIgnoreSurface
        logic.reset()
    }

    /** @return true, если слежение идёт. false: функция выключена пользователем или в телефоне нет акселерометра. */
    fun start(): Boolean {
        if (!prefs.stillEnabled) return false
        if (sensorListener != null) return true
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        reconfigure()
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.values.size >= 3) logic.onSample(e.timestamp, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val ok = try {
            // maxReportLatency: на телефонах с буфером датчика процессор спит, пока отсчёты копятся
            sm.registerListener(l, sensor, StillnessConfig.SAMPLING_US, StillnessConfig.MAX_LATENCY_US, handler)
        } catch (e: Exception) {
            false
        }
        if (!ok) return false
        manager = sm
        sensorListener = l
        return true
    }

    fun stop() {
        val l = sensorListener ?: return
        sensorListener = null
        try {
            manager?.unregisterListener(l)
        } catch (e: Exception) {
        }
        manager = null
        logic.reset()
    }

    /** Пользователь ответил «я в порядке»: считать неподвижность заново. */
    fun rearm() = logic.rearm()
}
