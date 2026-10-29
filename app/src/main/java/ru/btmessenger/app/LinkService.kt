package ru.btmessenger.app

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.StringRes

/**
 * Служба переднего плана: держит связь, пока приложение свёрнуто или экран выключен,
 * показывает постоянное уведомление и уведомления о входящих сообщениях.
 */
class LinkService : Service(), BtLink.Listener {

    private lateinit var link: BtLink
    private lateinit var notifier: Notifier
    private lateinit var advertiser: BleAdvertiser
    private lateinit var still: StillGuard

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        link = core.link
        notifier = core.notifier
        advertiser = BleAdvertiser(this, core.prefs)
        still = StillGuard(this, core.prefs, link, notifier, LocationHelper(this))
        link.addListener(this)
        link.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                still.stop()
                advertiser.stop()
                link.stop()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ACCEPT -> link.acceptIncoming()
            ACTION_REJECT -> link.rejectIncoming()
            ACTION_STILL_OK -> still.confirmOk()
        }
        // startForeground при каждом запуске: так требование startForegroundService() всегда выполнено
        if (!goForeground()) {
            still.stop()
            advertiser.stop()
            link.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        link.refresh()
        // BLE-объявление «я BtMessenger»: пока связь не выключена; дальше следим в onStateChanged
        advertiser.sync(link.state !is BtLink.State.Off)
        still.sync() // датчик неподвижности запускается только если пользователь включил функцию
        return START_STICKY
    }

    private fun goForeground(): Boolean {
        // Android 14: служба типа connectedDevice без разрешения Bluetooth не запускается (SecurityException)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            reportProblem(R.string.service_no_permission)
            return false
        }
        return try {
            val n = notifier.status(link.state)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForegroundTyped(n)
            } else {
                startForeground(Notifier.ID_STATUS, n)
            }
            core.serviceProblem = 0
            notifier.cancelProblem()
            true
        } catch (e: SecurityException) {
            // например, отозвали разрешение Bluetooth
            reportProblem(R.string.service_no_permission)
            false
        } catch (e: Exception) {
            // например, Android 12+ не разрешил запуск службы из фона
            reportProblem(R.string.service_start_failed)
            false
        }
    }

    /**
     * Тип connectedDevice; при включённом контроле неподвижности и выданной геопозиции ещё и location:
     * без него Android 10+ не отдаёт геопозицию службе, пока приложение свёрнуто (SOS ушёл бы без точки).
     * Если система не даёт запустить службу с типом location (например, запуск из фона), идём без него.
     */
    private fun startForegroundTyped(n: Notification) {
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (core.prefs.stillEnabled && hasLocation) {
            try {
                startForeground(Notifier.ID_STATUS, n, base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                return
            } catch (e: Exception) {
                // откатываемся на обычный тип
            }
        }
        startForeground(Notifier.ID_STATUS, n, base)
    }

    /** Служба не запустилась: экран покажет окно с причиной, а если приложение закрыто — уведомление. */
    private fun reportProblem(@StringRes res: Int) {
        core.serviceProblem = res
        notifier.showProblem(getString(res))
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        core.live.stopAll()
        still.stop()
        advertiser.stop()
        link.removeListener(this)
        link.stop()
        notifier.cancelRequest()
        super.onDestroy()
    }

    // ---------- BtLink.Listener ----------

    override fun onStateChanged(state: BtLink.State) {
        advertiser.sync(state !is BtLink.State.Off) // Bluetooth выключили или нет разрешения — объявление гасим
        notifier.updateStatus(state)
        if (state is BtLink.State.IncomingRequest) {
            if (!core.uiVisible) notifier.showRequest(state.peer)
        } else {
            notifier.cancelRequest()
        }
    }

    /** Название группы для уведомления (null — личное сообщение). */
    private fun groupNameOf(message: ChatMessage): String? =
        message.groupId?.let { core.repo.group(it)?.name }

    override fun onLinksChanged(connected: Set<String>) {
        still.onLinksChanged(connected)
    }

    override fun onMessageUpdated(message: ChatMessage, peer: BtLink.PeerInfo) {
        if (message.kind == Kind.SOS) notifier.updateSos(message, peer, groupNameOf(message))
    }

    // ---------- Рация: входящий звук играем сразу, без нажатий ----------

    override fun onVoiceStarted(peer: BtLink.PeerInfo, id: String, live: Boolean, chunkMs: Int) {
        if (live && !core.radioTx) core.live.begin(id, chunkMs)
    }

    override fun onVoiceChunk(peer: BtLink.PeerInfo, id: String, seq: Int, lostBefore: Int, data: ByteArray) {
        if (!core.radioTx) core.live.chunk(id, lostBefore, data)
    }

    override fun onVoiceLiveEnded(peer: BtLink.PeerInfo, id: String, lostTail: Int) {
        core.live.end(id)
    }

    override fun onIncoming(message: ChatMessage, peer: BtLink.PeerInfo) {
        // Чат сообщения — message.peer: адрес собеседника или ключ группы (автор группового — peer)
        when {
            message.kind == Kind.SOS -> notifier.showSos(message, peer, groupName = groupNameOf(message))
            !core.uiVisible || link.visibleChat != message.peer ->
                notifier.showMessage(message, peer, groupNameOf(message))
        }
    }

    companion object {
        const val ACTION_STOP = "ru.btmessenger.app.STOP"
        const val ACTION_ACCEPT = "ru.btmessenger.app.ACCEPT"
        const val ACTION_REJECT = "ru.btmessenger.app.REJECT"
        const val ACTION_STILL_OK = "ru.btmessenger.app.STILL_OK"
    }
}
