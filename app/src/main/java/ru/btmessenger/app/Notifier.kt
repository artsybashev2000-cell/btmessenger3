package ru.btmessenger.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

/** Уведомления: постоянное (работа службы), о сообщениях, о запросе подключения и тревожное SOS. */
class Notifier(private val ctx: Context) {
    private val nm: NotificationManager? = ctx.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val m = nm ?: return
        m.createNotificationChannel(
            NotificationChannel(CH_STATUS, ctx.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        m.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, ctx.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH)
        )
        m.createNotificationChannel(
            NotificationChannel(CH_REQUESTS, ctx.getString(R.string.channel_requests), NotificationManager.IMPORTANCE_HIGH)
        )
        m.createNotificationChannel(
            NotificationChannel(CH_STILL, ctx.getString(R.string.channel_still), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.channel_still_desc)
                enableVibration(true)
                vibrationPattern = SOS_VIBRATION
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        )
        m.createNotificationChannel(
            NotificationChannel(CH_SOS, ctx.getString(R.string.channel_sos), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.channel_sos_desc)
                enableVibration(true)
                vibrationPattern = SOS_VIBRATION
                enableLights(true)
                lightColor = 0xFFE53935.toInt()
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        )
    }

    /** Можно ли сейчас показать уведомление-предупреждение (разрешение, уведомления и канал не выключены). */
    fun canWarn(): Boolean {
        if (!canNotify() || nm?.areNotificationsEnabled() != true) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm?.getNotificationChannel(CH_STILL)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return false
        return true
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun builder(channel: String, highPriority: Boolean): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, channel)
        } else {
            Notification.Builder(ctx).apply {
                if (highPriority) {
                    setPriority(Notification.PRIORITY_HIGH)
                    setDefaults(Notification.DEFAULT_ALL)
                } else {
                    setPriority(Notification.PRIORITY_LOW)
                }
            }
        }

    private fun openApp(peer: String?, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (peer != null) intent.putExtra(MainActivity.EXTRA_PEER, peer)
        return PendingIntent.getActivity(
            ctx, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            ctx, requestCode, Intent(ctx, LinkService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    // ---------- Постоянное уведомление службы ----------

    fun status(state: BtLink.State): Notification {
        val text = when (state) {
            is BtLink.State.Off -> ctx.getString(
                when (state.reason) {
                    BtLink.OffReason.BLUETOOTH_OFF -> R.string.status_bt_off
                    BtLink.OffReason.NO_PERMISSION -> R.string.status_no_permission
                    BtLink.OffReason.NO_ADAPTER -> R.string.status_no_adapter
                    BtLink.OffReason.STOPPED -> R.string.status_starting
                }
            )
            is BtLink.State.Idle -> ctx.getString(R.string.status_idle)
            is BtLink.State.Connecting -> if (state.auto) {
                ctx.getString(R.string.status_reconnecting, state.peer.title, state.attempt)
            } else {
                ctx.getString(R.string.status_connecting, state.peer.title)
            }
            is BtLink.State.WaitingAccept -> ctx.getString(R.string.status_waiting_accept, state.peer.title)
            is BtLink.State.IncomingRequest -> ctx.getString(R.string.status_request, state.peer.title)
            is BtLink.State.Connected -> ctx.getString(R.string.status_connected, state.peer.title)
        }
        val stop = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_power),
            ctx.getString(R.string.action_turn_off),
            serviceAction(LinkService.ACTION_STOP, RC_STOP)
        ).build()
        return builder(CH_STATUS, highPriority = false)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp(state.peerAddress.takeIf { state is BtLink.State.Connected }, RC_OPEN))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(stop)
            .build()
    }

    fun updateStatus(state: BtLink.State) {
        if (!canNotify()) return
        nm?.notify(ID_STATUS, status(state))
    }

    // ---------- Сообщения ----------

    /**
     * Уведомление о сообщении. Чат определяется по [ChatMessage.peer]: адрес собеседника или ключ группы.
     * Для группы передают её название [groupName]: оно становится заголовком, а автор — началом текста.
     */
    fun showMessage(m: ChatMessage, peer: BtLink.PeerInfo, groupName: String? = null) {
        if (!canNotify()) return
        val body = when (m.kind) {
            Kind.GEO -> ctx.getString(R.string.preview_geo)
            Kind.VOICE -> ctx.getString(R.string.preview_voice, Format.mmss(m.voiceMs.toLong()))
            else -> m.text
        }
        val text = if (groupName != null) ctx.getString(R.string.preview_author, peer.title, body) else body
        val n = builder(CH_MESSAGES, highPriority = true)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(groupName ?: peer.title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(m.peer, chatId(m.peer)))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        nm?.notify(chatId(m.peer), n)
    }

    /** Звучит ли канал тревоги при режиме «Не беспокоить» (это включает только сам пользователь). */
    fun sosBypassesDnd(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm?.getNotificationChannel(CH_SOS)?.canBypassDnd() == true

    /** Обновить показанное уведомление SOS (точка уточнилась) — без повторного звука и без воскрешения закрытого. */
    fun updateSos(m: ChatMessage, peer: BtLink.PeerInfo, groupName: String? = null) {
        val id = sosId(m.peer)
        if (nm?.activeNotifications?.any { it.id == id } == true) showSos(m, peer, quiet = true, groupName = groupName)
    }

    /** [groupName] — название группы, если SOS пришёл в группу (тогда уведомление ведёт в чат группы). */
    fun showSos(m: ChatMessage, peer: BtLink.PeerInfo, quiet: Boolean = false, groupName: String? = null) {
        if (!canNotify()) return
        val text = Format.sosSummary(ctx, m)
        @Suppress("DEPRECATION")
        val b = builder(CH_SOS, highPriority = true)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(
                if (groupName != null) ctx.getString(R.string.sos_from_group, peer.title, groupName)
                else ctx.getString(R.string.sos_from, peer.title)
            )
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(m.peer, sosId(m.peer)))
            .setAutoCancel(true)
            .setOnlyAlertOnce(quiet)
            .setCategory(Notification.CATEGORY_ALARM)
            .setColor(0xFFE53935.toInt())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            b.setVibrate(SOS_VIBRATION)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        }
        nm?.notify(sosId(m.peer), b.build())
    }

    fun cancelChat(peer: String) {
        nm?.cancel(chatId(peer))
        nm?.cancel(sosId(peer))
    }

    // ---------- Запрос на подключение ----------

    fun showRequest(peer: BtLink.PeerInfo) {
        if (!canNotify()) return
        val accept = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_check),
            ctx.getString(R.string.accept),
            serviceAction(LinkService.ACTION_ACCEPT, RC_ACCEPT)
        ).build()
        val reject = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_close),
            ctx.getString(R.string.reject),
            serviceAction(LinkService.ACTION_REJECT, RC_REJECT)
        ).build()
        val n = builder(CH_REQUESTS, highPriority = true)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(ctx.getString(R.string.request_title))
            .setContentText(Format.peerDescription(ctx, peer))
            .setContentIntent(openApp(null, RC_REQUEST))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(accept)
            .addAction(reject)
            .build()
        nm?.notify(ID_REQUEST, n)
    }

    fun cancelRequest() {
        nm?.cancel(ID_REQUEST)
    }

    // ---------- «Человек не двигается» ----------

    private fun stillOkAction(): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_check),
            ctx.getString(R.string.still_ok),
            serviceAction(LinkService.ACTION_STILL_OK, RC_STILL_OK)
        ).build()

    /**
     * Громкое предупреждение с обратным отсчётом (таймер в уведомлении идёт сам). Кнопка «Я в порядке»
     * и нажатие на само уведомление отменяют SOS.
     */
    @Suppress("DEPRECATION")
    fun showStillAlert(stillSeconds: Int, replySeconds: Int) {
        if (!canNotify()) return
        val b = builder(CH_STILL, highPriority = true)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(ctx.getString(R.string.still_alert_title))
            .setContentText(ctx.getString(R.string.still_alert_text, Format.mmss(stillSeconds * 1000L)))
            .setStyle(Notification.BigTextStyle().bigText(ctx.getString(R.string.still_alert_text, Format.mmss(stillSeconds * 1000L))))
            .setContentIntent(serviceAction(LinkService.ACTION_STILL_OK, RC_STILL_OK))
            .setAutoCancel(true)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_ALARM)
            .setColor(0xFFE53935.toInt())
            .setWhen(System.currentTimeMillis() + replySeconds * 1000L)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .addAction(stillOkAction())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setVibrate(SOS_VIBRATION).setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        }
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // звук повторяется, пока уведомление не снято
        nm?.notify(ID_STILL, n)
    }

    /** SOS отправлен: говорим, сколько собеседников его получили (по связи, подтверждение придёт отдельно). */
    fun showStillSent(count: Int) {
        if (!canNotify()) return
        val n = builder(CH_MESSAGES, highPriority = true)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(ctx.getString(R.string.still_sent_title))
            .setContentText(ctx.getString(R.string.still_sent_text, count))
            .setContentIntent(openApp(null, RC_OPEN))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        nm?.notify(ID_STILL_RESULT, n)
    }

    /** Ни с кем нет связи: SOS ждёт; его ещё можно отменить. */
    fun showStillWaiting() {
        if (!canNotify()) return
        val n = builder(CH_MESSAGES, highPriority = true)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(ctx.getString(R.string.still_waiting_title))
            .setContentText(ctx.getString(R.string.still_waiting_text))
            .setStyle(Notification.BigTextStyle().bigText(ctx.getString(R.string.still_waiting_text)))
            .setContentIntent(serviceAction(LinkService.ACTION_STILL_OK, RC_STILL_OK))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .addAction(stillOkAction())
            .build()
        nm?.notify(ID_STILL_RESULT, n)
    }

    fun showStillGaveUp() {
        if (!canNotify()) return
        val n = builder(CH_MESSAGES, highPriority = true)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(ctx.getString(R.string.still_gaveup_title))
            .setContentText(ctx.getString(R.string.still_gaveup_text))
            .setContentIntent(openApp(null, RC_OPEN))
            .setAutoCancel(true)
            .build()
        nm?.notify(ID_STILL_RESULT, n)
    }

    /** Снять само предупреждение (отсчёт). */
    fun cancelStillAlert() {
        nm?.cancel(ID_STILL)
    }

    /** Снять всё, что связано с неподвижностью, включая итоговое уведомление. */
    fun cancelStill() {
        nm?.cancel(ID_STILL)
        nm?.cancel(ID_STILL_RESULT)
    }

    // ---------- Проблема с запуском службы ----------

    /** Служба не запустилась (например, нет разрешения): объясняем, что делать. Нужно разрешение на уведомления. */
    fun showProblem(text: String) {
        if (!canNotify()) return
        val n = builder(CH_MESSAGES, highPriority = true)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(ctx.getString(R.string.service_problem_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(null, RC_PROBLEM))
            .setAutoCancel(true)
            .build()
        nm?.notify(ID_PROBLEM, n)
    }

    fun cancelProblem() {
        nm?.cancel(ID_PROBLEM)
    }

    private fun chatId(peer: String) = 1000 + (peer.hashCode() and 0xFFFF)
    private fun sosId(peer: String) = 100_000 + (peer.hashCode() and 0xFFFF)

    companion object {
        const val ID_STATUS = 1
        const val ID_REQUEST = 2
        private const val ID_PROBLEM = 3
        private const val ID_STILL = 4
        private const val ID_STILL_RESULT = 5
        private const val CH_STATUS = "status"
        private const val CH_MESSAGES = "messages"
        private const val CH_REQUESTS = "requests"
        const val CH_SOS = "sos"
        private const val CH_STILL = "still"
        private const val RC_OPEN = 1
        private const val RC_STOP = 2
        private const val RC_ACCEPT = 3
        private const val RC_REJECT = 4
        private const val RC_REQUEST = 5
        private const val RC_PROBLEM = 6
        private const val RC_STILL_OK = 7
        private val SOS_VIBRATION = longArrayOf(0, 800, 400, 800, 400, 800, 400, 800)
    }
}
