package ru.btmessenger.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * «Человек не двигается»: связывает датчик [StillnessDetector] с предупреждением и SOS.
 * Живёт в [LinkService] (служба переднего плана), поэтому работает и при выключенном экране.
 * Все вызовы идут из главного потока.
 *
 * 1. Датчик сообщил о неподвижности: громкое уведомление, вибрация и обратный отсчёт (время ответа из настроек).
 * 2. «Я в порядке» (кнопка или нажатие на само уведомление) всё отменяет и начинает отсчёт неподвижности заново.
 * 3. Не ответил: SOS уходит всем, с кем сейчас есть связь, с последней известной точкой, затем точка уточняется.
 *    Если связи нет ни с кем, SOS ждёт до [WAIT_LINK_MS] и уходит тем, с кем связь появится.
 * Если предупреждение показать нельзя (нет уведомлений), датчик не запускается: SOS без предупреждения не отправляем.
 */
class StillGuard(
    private val ctx: Context,
    private val prefs: Prefs,
    private val link: BtLink,
    private val notifier: Notifier,
    private val location: LocationHelper
) {
    private enum class Phase { IDLE, COUNTDOWN, WAITING_LINK }

    private val handler = Handler(Looper.getMainLooper())
    private val detector = StillnessDetector(ctx, prefs)
    private var phase = Phase.IDLE
    private var fixRequest: LocationHelper.Request? = null
    private var appliedConfig: List<Any>? = null

    private val timeout = Runnable { onTimeout() }
    private val giveUp = Runnable { onGiveUp() }

    init {
        detector.listener = object : StillnessDetector.Listener {
            override fun onStill(seconds: Int) {
                this@StillGuard.onStill(seconds)
            }
        }
    }

    /** Привести датчик в соответствие с настройками (звать при каждом запуске службы). */
    fun sync() {
        if (!prefs.stillEnabled || !notifier.canWarn()) {
            cancelAll()
            detector.stop()
            appliedConfig = null
            return
        }
        val config = listOf<Any>(prefs.stillSeconds, prefs.stillSensitivity, prefs.stillIgnoreSurface)
        if (!detector.isRunning) {
            if (detector.start()) appliedConfig = config
        } else if (appliedConfig != config && phase == Phase.IDLE) {
            detector.reconfigure()
            appliedConfig = config
        }
    }

    fun stop() {
        cancelAll()
        detector.stop()
        appliedConfig = null
    }

    /** Пользователь ответил «Я в порядке». */
    fun confirmOk() {
        val wasActive = phase != Phase.IDLE
        cancelAll()
        notifier.cancelStill() // и итоговое уведомление, если оно висит
        if (wasActive) detector.rearm()
    }

    /** Изменился набор подключённых собеседников. */
    fun onLinksChanged(connected: Set<String>) {
        if (phase == Phase.WAITING_LINK && connected.isNotEmpty()) {
            handler.removeCallbacks(giveUp)
            dispatch(connected.toList())
        }
    }

    private fun onStill(seconds: Int) {
        if (phase != Phase.IDLE) return
        phase = Phase.COUNTDOWN
        val reply = prefs.stillReplySeconds
        notifier.showStillAlert(seconds, reply)
        startVibration()
        handler.postDelayed(timeout, reply * 1000L)
    }

    private fun onTimeout() {
        if (phase != Phase.COUNTDOWN) return
        stopVibration()
        notifier.cancelStillAlert()
        val targets = link.connectedAddresses.toList()
        if (targets.isEmpty()) {
            phase = Phase.WAITING_LINK
            notifier.showStillWaiting()
            handler.postDelayed(giveUp, WAIT_LINK_MS)
        } else {
            dispatch(targets)
        }
    }

    private fun onGiveUp() {
        if (phase != Phase.WAITING_LINK) return
        phase = Phase.IDLE
        notifier.showStillGaveUp()
    }

    private fun dispatch(targets: List<String>) {
        phase = Phase.IDLE
        val text = ctx.getString(R.string.still_sos_text)
        val first = location.lastKnown() // сразу уходит с последней известной точкой, получатель видит её возраст
        val sent = targets.associateWith { link.sendSos(it, text, first) }
        notifier.showStillSent(sent.size)
        if (location.hasPermission() && location.isEnabled()) {
            // свежая точка обновляет те же сообщения
            fixRequest = location.requestFix { geo ->
                fixRequest = null
                if (geo != null) sent.forEach { (address, id) -> link.refreshSosGeo(address, id, geo) }
            }
        }
    }

    private fun cancelAll() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(giveUp)
        stopVibration()
        notifier.cancelStillAlert()
        fixRequest?.cancel()
        fixRequest = null
        phase = Phase.IDLE
    }

    // ---------- Вибрация на время отсчёта ----------

    private var vibrator: Vibrator? = null

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        vibrator = v
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(PATTERN, 0), attrs) // 0 — повторять с начала
            } else {
                v.vibrate(PATTERN, 0, attrs)
            }
        } catch (e: Exception) {
            vibrator = null
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
        }
        vibrator = null
    }

    private companion object {
        /** Сколько ждём связи с кем-нибудь, если на момент отправки никого нет на связи. */
        const val WAIT_LINK_MS = 30 * 60_000L
        val PATTERN = longArrayOf(0, 700, 300, 700, 300, 1200, 800)
    }
}
