package ru.btmessenger.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Движок связи по Bluetooth (RFCOMM). Живёт всё время работы приложения, а держит его
 * в памяти [LinkService] — поэтому связь больше не рвётся при сворачивании,
 * повороте экрана, смене шрифта и т. п.
 *
 * Вся логика выполняется на одном фоновом потоке [h], поэтому состояние не нужно
 * защищать блокировками. Чтение/запись сокетов — на отдельных потоках.
 * Слушатели вызываются на главном потоке.
 */
@SuppressLint("MissingPermission")
class BtLink(
    private val ctx: Context,
    private val prefs: Prefs,
    private val repo: ChatRepository
) {
    data class PeerInfo(val address: String, val name: String, val role: String, val deviceName: String) {
        val title: String get() = displayName(name, deviceName, address)
    }

    enum class OffReason { STOPPED, NO_ADAPTER, NO_PERMISSION, BLUETOOTH_OFF }

    sealed class State {
        data class Off(val reason: OffReason) : State()

        /** Ждём входящих подключений. */
        object Idle : State()

        /** Устанавливаем соединение (attempt > 0 — автоматическое переподключение). */
        data class Connecting(val peer: PeerInfo, val attempt: Int, val auto: Boolean) : State()

        /** Соединились, ждём, пока собеседник примет подключение. */
        data class WaitingAccept(val peer: PeerInfo) : State()

        /** Кто-то незнакомый хочет подключиться — нужно решение пользователя. */
        data class IncomingRequest(val peer: PeerInfo) : State()

        data class Connected(val peer: PeerInfo) : State()

        val peerAddress: String?
            get() = when (this) {
                is Connecting -> peer.address
                is WaitingAccept -> peer.address
                is IncomingRequest -> peer.address
                is Connected -> peer.address
                else -> null
            }
    }

    interface Listener {
        fun onStateChanged(state: State) {}
        fun onMessagesChanged(peer: String) {}
        fun onIncoming(message: ChatMessage, peer: PeerInfo) {}

        /** Пришла более свежая точка для уже полученного SOS (новое сообщение не создаётся). */
        fun onMessageUpdated(message: ChatMessage, peer: PeerInfo) {}
        fun onNotice(text: String) {}

        /** Изменился набор собеседников, с которыми есть принятое соединение (адреса Bluetooth). */
        fun onLinksChanged(connected: Set<String>) {}

        /** Собеседник начал голосовую передачу: [live] — рация, иначе голосовое сообщение. */
        fun onVoiceStarted(peer: PeerInfo, id: String, live: Boolean, chunkMs: Int) {}

        /**
         * Рация: очередной кусок звука. [lostBefore] — сколько кусков перед ним потерялось
         * (плееру нужно подставить на их место маскировку потери; ждать их не надо).
         */
        fun onVoiceChunk(peer: PeerInfo, id: String, seq: Int, lostBefore: Int, data: ByteArray) {}

        /** Рация закончилась (или оборвалась). [lostTail] — сколько кусков в конце так и не пришло. */
        fun onVoiceLiveEnded(peer: PeerInfo, id: String, lostTail: Int) {}

        /** Голосовое сообщение принято целиком (полнота проверена по номерам, подтверждение отправлено). */
        fun onVoiceMessage(peer: PeerInfo, id: String, chunkMs: Int, sentAt: Long, chunks: List<ByteArray>) {}

        /** Голосовое сообщение не удалось получить целиком ([incoming] = true) или отправить ([incoming] = false). */
        fun onVoiceFailed(peer: PeerInfo, id: String, incoming: Boolean) {}

        /** Своё голосовое сообщение доставлено (собеседник подтвердил). */
        fun onVoiceDelivered(peer: PeerInfo, id: String) {}
    }

    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val thread = HandlerThread("bt-link").apply { start() }
    private val h = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Текущее состояние (можно читать с любого потока). */
    @Volatile
    var state: State = State.Off(if (adapter == null) OffReason.NO_ADAPTER else OffReason.STOPPED)
        private set

    /**
     * Адрес чата, открытого на экране (для него не нужны уведомления и счётчик непрочитанных).
     * Если с этим собеседником есть соединение, [state] описывает именно его (см. computeState).
     */
    @Volatile
    var visibleChat: String? = null
        set(value) {
            field = value
            h.post { publish() }
        }

    /** Адреса, с которыми сейчас есть принятое соединение (снимок; меняется вместе с [Listener.onLinksChanged]). */
    @Volatile
    var connectedAddresses: Set<String> = emptySet()
        private set

    /** Сколько соединений можно держать одновременно. */
    val maxLinks: Int get() = MAX_LINKS

    val hasAdapter: Boolean get() = adapter != null

    // ---------- Состояние: трогать только на потоке h ----------
    private var started = false
    private var receiverRegistered = false
    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null // дозваниваемся одновременно только к одному

    /** Кому звоним: тот, к кому идёт connectThread, и очередь за ним (порядок добавления). */
    private val dialing = LinkedHashSet<String>()

    /** Все сокеты, включая ещё не приславшие HELLO. */
    private val conns = mutableListOf<Conn>()

    /** Соединения после HELLO: адрес → соединение (не больше [MAX_LINKS]). Принято или нет — см. [Conn.accepted]. */
    private val links = LinkedHashMap<String, Conn>()
    private var connSeq = 0

    /** Автопереподключение у каждого собеседника своё: адрес → состояние попыток. */
    private val reconnects = HashMap<String, Reconnect>()

    /** Кого восстанавливать, когда Bluetooth снова включат (мы были инициатором связи). */
    private val resumeTargets = LinkedHashSet<String>()

    /** Групповые SOS с обновлённой точкой: id → кому из участников новая точка ещё не доставлена. */
    private val groupGeoOwed = HashMap<String, GeoOwed>()

    private inner class Reconnect(val address: String) {
        val startedAt = SystemClock.elapsedRealtime()
        var attempt = 0
        val task = Runnable { onReconnectDue(address) }
    }

    private class GeoOwed(val fixTime: Long, val ids: MutableSet<String>)

    /** SOS, у которых точка обновлена после первой отправки: id → время замера новой точки. */
    private val geoDirty = HashMap<String, Long>()

    /** Кому мы нажали «подключиться» только что: его встречное подключение примем без вопроса. */
    private val recentDial = HashMap<String, Long>()

    /** Недавно пересланные чужие кадры (автор + id): второй раз их не пересылаем, петли и дубли по двум путям гасятся. */
    private val seenRelayed = SeenCache()

    /** Свои голосовые сообщения, которые собеседник ещё не подтвердил: id → сообщение. Живут в памяти. */
    private val voiceOut = LinkedHashMap<String, VoiceOut>()

    /** Недавно принятые целиком голосовые сообщения: повторную отправку только подтверждаем. */
    private val voiceDone = LinkedHashSet<String>()

    private class VoiceOut(val id: String, val peer: String, val chunkMs: Int, val sentAt: Long, val chunks: List<ByteArray>) {
        var resends = 0
    }

    /** Входящая голосовая передача: рация ([gap]) или сообщение ([asm]). */
    private class VoiceIn(val id: String, val live: Boolean, val chunkMs: Int, val sentAt: Long, now: Long) {
        val gap: LiveGapTracker? = if (live) LiveGapTracker() else null
        val asm: VoiceAssembler? = if (live) null else VoiceAssembler()
        var lastRx = now
        var nacks = 0
    }

    /**
     * Ручка исходящей рации ([startLive]). [push] — отдать очередной кусок с рекордера (можно из любого
     * потока), [end] — закончить (звать после остановки рекордера). Если связи нет или собеседник старой
     * версии, ручка сразу неактивна ([isActive] = false), причина приходит через onNotice.
     */
    inner class LiveVoice internal constructor(val id: String, private val peer: String) {
        @Volatile
        var isActive = true
            internal set
        private var next = 0

        @Synchronized
        fun push(chunk: ByteArray) {
            if (!isActive || chunk.isEmpty() || chunk.size > Protocol.MAX_VOICE_CHUNK_BYTES) return
            val n = next++
            if (n > Protocol.MAX_VOICE_SEQ) return
            h.post { if (isActive) sendLiveChunk(this, peer, n, chunk) }
        }

        @Synchronized
        fun end() {
            if (!isActive) return
            isActive = false
            val count = next
            h.post { links[peer]?.takeIf { it.accepted }?.send(Frame.VoiceEnd(id, count)) }
        }
    }

    // ---------- Публичные методы (можно вызывать с главного потока) ----------

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun start() {
        h.post { doStart() }
    }

    fun stop() {
        h.post { doStop() }
    }

    /**
     * Подключиться к телефону. По умолчанию (личный чат) соединения с другими собеседниками
     * закрываются, как раньше; для группы передают [keepOthers] = true — тогда они остаются.
     */
    fun connect(address: String, keepOthers: Boolean = false) {
        h.post { doConnect(address, keepOthers) }
    }

    /**
     * Отключиться осознанно: собеседник получит BYE и не будет переподключаться.
     * Без адреса отключается то соединение, которое сейчас показывает [state] (как раньше, когда оно было одно).
     */
    fun disconnect() {
        h.post { doDisconnect(null) }
    }

    fun disconnect(address: String) {
        h.post { doDisconnect(address) }
    }

    fun disconnectAll() {
        h.post { doDisconnectAll() }
    }

    /** Принять/отклонить запрос, который сейчас показывает [state] (IncomingRequest). */
    fun acceptIncoming() {
        h.post { doAcceptIncoming() }
    }

    fun rejectIncoming() {
        h.post { doRejectIncoming(Frame.REJECT_DECLINED) }
    }

    /** Перечитать разрешения/состояние адаптера (например, после выдачи разрешений). */
    fun refresh() {
        h.post {
            if (started) {
                startListening()
                resumeIfNeeded()
            }
            publish()
        }
    }

    // Сообщения сначала сохраняются, а уходят, когда есть связь. Ничего не теряется молча.
    fun sendText(peer: String, text: String) {
        h.post { enqueue(peer, Kind.TEXT, text, null) }
    }

    fun sendGeo(peer: String, geo: GeoPoint) {
        h.post { enqueue(peer, Kind.GEO, "", geo) }
    }

    /** @return id сообщения — по нему потом можно уточнить точку через [refreshSosGeo]. */
    fun sendSos(peer: String, text: String, geo: GeoPoint?): String {
        val id = UUID.randomUUID().toString()
        h.post { enqueue(peer, Kind.SOS, text, geo, id) }
        return id
    }

    /** Заменить точку в уже отправленном (или ждущем связи) SOS более свежей. */
    fun refreshSosGeo(peer: String, id: String, geo: GeoPoint) {
        h.post { doRefreshGeo(peer, id, geo) }
    }

    // Группа: сообщение уходит всем подключённым участникам, остальным — когда подключатся.
    // Беседа в базе — groupChatKey(groupId); статус «доставлено» по каждому участнику ведёт repo.groupDelivery.
    fun sendGroupText(groupId: String, text: String) {
        h.post { enqueue(groupChatKey(groupId), Kind.TEXT, text, null, groupId = groupId) }
    }

    fun sendGroupGeo(groupId: String, geo: GeoPoint) {
        h.post { enqueue(groupChatKey(groupId), Kind.GEO, "", geo, groupId = groupId) }
    }

    /** @return id сообщения — по нему потом можно уточнить точку через [refreshGroupSosGeo]. */
    fun sendGroupSos(groupId: String, text: String, geo: GeoPoint?): String {
        val id = UUID.randomUUID().toString()
        h.post { enqueue(groupChatKey(groupId), Kind.SOS, text, geo, id, groupId) }
        return id
    }

    fun refreshGroupSosGeo(groupId: String, id: String, geo: GeoPoint) {
        h.post { doRefreshGroupGeo(groupId, id, geo) }
    }

    /**
     * Создать группу из знакомых собеседников ([memberAddresses] — адреса Bluetooth) и разослать приглашения:
     * тем, кто на связи, — сразу, остальным — при подключении (к ним мы звоним сами).
     * [onResult] вызывается на главном потоке: id созданной группы или null, если создать не вышло
     * (пустое название, нет ни одного участника с известным id установки).
     */
    fun createGroup(name: String, memberAddresses: List<String>, onResult: (String?) -> Unit) {
        h.post {
            val id = doCreateGroup(name, memberAddresses)
            main.post { onResult(id) }
        }
    }

    /**
     * Выйти из группы: участники на связи получают GROUP_LEAVE, группа и её переписка удаляются у нас.
     * Тем, кто не на связи, мы ответим «я вышел» при следующем подключении (см. [Prefs.hasLeftGroup]).
     */
    fun leaveGroup(groupId: String) {
        h.post { doLeaveGroup(groupId) }
    }

    fun clearHistory(peer: String) {
        h.post {
            voiceOut.values.removeAll { it.peer == peer }
            repo.clearHistory(peer)
            emitMessages(peer)
        }
    }

    fun forgetPeer(peer: String) {
        h.post {
            if (links.containsKey(peer) || peer in dialing) doDisconnect(peer)
            resumeTargets.remove(peer)
            cancelReconnect(peer)
            recentDial.remove(peer)
            prefs.untrust(peer)
            voiceOut.values.removeAll { it.peer == peer }
            repo.deletePeer(peer)
            emitMessages(peer)
        }
    }

    // ---------- Запуск и остановка ----------

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            h.post { onBluetoothState(s) }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            if (started) h.postDelayed(this, TICK_MS)
        }
    }

    private val retryListen = Runnable { startListening() }

    /** Подошло время очередной попытки переподключения к [address]. */
    private fun onReconnectDue(address: String) {
        val r = reconnects[address] ?: return
        if (links.containsKey(address)) {
            cancelReconnect(address)
            publish()
            return
        }
        if (!bluetoothReady()) {
            // Bluetooth выключили раньше, чем пришла рассылка об этом: запоминаем цель на потом
            resumeTargets.add(address)
            cancelReconnect(address)
            publish()
            return
        }
        r.attempt++
        if (isFullFor(address)) {
            scheduleReconnect(address) // мест нет: дождёмся, пока кто-нибудь отключится
            return
        }
        startOutgoing(address)
    }

    private fun doStart() {
        if (started) return
        started = true
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                ctx, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        }
        startListening()
        h.removeCallbacks(tick)
        h.postDelayed(tick, TICK_MS)
        publish()
    }

    private fun doStop() {
        if (!started) return
        started = false
        resumeTargets.clear()
        voiceOut.clear()
        h.removeCallbacks(tick)
        h.removeCallbacks(retryListen)
        cancelAllReconnects()
        connectThread?.cancel()
        connectThread = null
        dialing.clear()
        acceptThread?.cancel()
        acceptThread = null
        leaveAll()
        if (receiverRegistered) {
            try {
                ctx.unregisterReceiver(btReceiver)
            } catch (e: IllegalArgumentException) {
            }
            receiverRegistered = false
        }
        publish()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun bluetoothReady(): Boolean {
        val a = adapter ?: return false
        return hasConnectPermission() && a.isEnabled
    }

    private fun onBluetoothState(s: Int) {
        when (s) {
            BluetoothAdapter.STATE_ON -> {
                startListening()
                resumeIfNeeded()
            }
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                val hadLink = links.values.any { it.accepted } || dialing.isNotEmpty() || reconnects.isNotEmpty()
                // Восстанавливаем потом только то, что начали мы: второй телефон сам не звонит
                for (c in links.values) if (c.accepted && c.outgoing) resumeTargets.add(c.address)
                resumeTargets.addAll(reconnects.keys)
                cancelAllReconnects()
                connectThread?.cancel()
                connectThread = null
                dialing.clear()
                acceptThread?.cancel()
                acceptThread = null
                h.removeCallbacks(retryListen)
                links.clear()
                for (c in conns) c.closeNow()
                conns.clear()
                if (hadLink) notice(R.string.notice_bt_turned_off)
            }
        }
        publish()
    }

    /** Bluetooth снова включён: один раз запускаем переподключение ко всем, с кем была связь. */
    private fun resumeIfNeeded() {
        if (resumeTargets.isEmpty() || !started || !bluetoothReady()) return
        val targets = resumeTargets.toList()
        resumeTargets.clear()
        for (t in targets) {
            if (links.containsKey(t) || t in dialing || t in reconnects) continue // уже что-то идёт
            startReconnect(t)
        }
    }

    // ---------- Приём входящих ----------

    private fun startListening() {
        if (!started || acceptThread != null || !bluetoothReady()) return
        h.removeCallbacks(retryListen)
        val server = try {
            adapter?.listenUsingInsecureRfcommWithServiceRecord(Protocol.SERVICE_NAME, Protocol.APP_UUID)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
        if (server == null) {
            h.postDelayed(retryListen, RETRY_LISTEN_MS)
            return
        }
        acceptThread = AcceptThread(server).also { it.start() }
    }

    private inner class AcceptThread(private val server: BluetoothServerSocket) : Thread("bt-accept") {
        override fun run() {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                h.post { onIncomingSocket(socket) }
            }
            closeQuietly(server) // не оставляем открытый серверный сокет, если accept() упал сам
            h.post { onAcceptEnded(this) }
        }

        fun cancel() = closeQuietly(server)
    }

    private fun onAcceptEnded(t: AcceptThread) {
        if (acceptThread !== t) return
        acceptThread = null
        if (started && bluetoothReady()) h.postDelayed(retryListen, RETRY_LISTEN_MS)
    }

    private fun onIncomingSocket(socket: BluetoothSocket) {
        if (!started) {
            closeQuietly(socket)
            return
        }
        val address = socket.remoteDevice.address
        val c = openConn(socket, outgoing = false) ?: return
        if (isFullFor(address) || conns.size >= MAX_LINKS * 2) {
            // Все места заняты — вежливо отказываем, чтобы у него было понятное сообщение.
            c.send(helloFrame())
            c.closeGracefully(Frame.Reject(Frame.REJECT_BUSY))
            notice(R.string.notice_incoming_over_limit, titleFor(address), MAX_LINKS)
            return
        }
        addConn(c)
    }

    /** Нет места для нового собеседника: [MAX_LINKS] соединений уже занято другими адресами. */
    private fun isFullFor(address: String): Boolean = !links.containsKey(address) && links.size >= MAX_LINKS

    // ---------- Исходящие ----------

    private fun doConnect(address: String, keepOthers: Boolean) {
        if (!started || !bluetoothReady()) {
            notice(R.string.notice_bt_not_ready)
            publish()
            return
        }
        resumeTargets.remove(address)
        recentDial[address] = SystemClock.elapsedRealtime() + RECENT_DIAL_MS
        if (!keepOthers) dropOthers(address) // личный чат: как раньше, один собеседник
        val cur = links[address]
        if (cur != null) {
            // Нажали «подключиться» тому, чей запрос сейчас на экране, — это согласие
            if (!cur.outgoing && !cur.accepted && cur.hello != null) acceptConn(cur)
            publish()
            return
        }
        if (address in dialing) {
            publish() // уже дозваниваемся (или стоим в очереди)
            return
        }
        // Место считается и за тех, с кем связь восстанавливается: они вот-вот вернутся
        val occupied = HashSet<String>().apply { addAll(links.keys); addAll(dialing); addAll(reconnects.keys) }
        if (address !in occupied && occupied.size >= MAX_LINKS) {
            notice(R.string.notice_too_many_links, MAX_LINKS)
            publish()
            return
        }
        cancelReconnect(address)
        // Доверенным адрес становится только после ACCEPT (см. onFrame), а не по нажатию кнопки.
        // Ответное подключение от того, кому звоним, принимается через dialing (см. onHello).
        startOutgoing(address)
    }

    /** Закрыть всё, что связано с другими собеседниками (соединения, попытки дозвона, переподключения). */
    private fun dropOthers(keep: String) {
        for (c in links.values.toList()) {
            if (c.address == keep) continue
            links.remove(c.address)
            conns.remove(c)
            c.closeGracefully(farewell(c))
        }
        dialing.retainAll { it == keep }
        for (a in reconnects.keys.toList()) if (a != keep) cancelReconnect(a)
        val t = connectThread
        if (t != null && t.device.address != keep) {
            t.cancel()
            connectThread = null
        }
    }

    /** Поставить собеседника в очередь на дозвон. Сам дозвон идёт по одному — см. [pumpDial]. */
    private fun startOutgoing(address: String) {
        if (adapter == null) return
        dialing.add(address)
        pumpDial()
        publish()
    }

    /** Запустить следующий дозвон из очереди, если сейчас никому не звоним. */
    private fun pumpDial() {
        if (connectThread != null) return
        val a = adapter ?: return
        val address = dialing.firstOrNull { addr -> conns.none { it.address == addr } } ?: return
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            dialing.remove(address)
            pumpDial()
            return
        }
        try {
            a.cancelDiscovery()
        } catch (e: SecurityException) {
        }
        repo.upsertPeer(address, null, null, null, deviceNameOf(device))
        connectThread = ConnectThread(device).also { it.start() }
    }

    private inner class ConnectThread(val device: BluetoothDevice) : Thread("bt-connect") {
        @Volatile
        private var socket: BluetoothSocket? = null

        @Volatile
        private var cancelled = false

        override fun run() {
            val s = try {
                device.createInsecureRfcommSocketToServiceRecord(Protocol.APP_UUID)
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
            socket = s
            if (s == null || cancelled) {
                s?.let { closeQuietly(it) }
                h.post { onConnectResult(this, null) }
                return
            }
            val ok = try {
                s.connect()
                true
            } catch (e: IOException) {
                false
            } catch (e: SecurityException) {
                false
            }
            if (!ok || cancelled) closeQuietly(s)
            h.post { onConnectResult(this, if (ok && !cancelled) s else null) }
        }

        fun cancel() {
            cancelled = true
            socket?.let { closeQuietly(it) }
        }
    }

    private fun onConnectResult(t: ConnectThread, socket: BluetoothSocket?) {
        if (t !== connectThread) {
            socket?.let { closeQuietly(it) }
            return
        }
        connectThread = null
        val address = t.device.address
        if (socket == null || !started) {
            socket?.let { closeQuietly(it) }
            outgoingFailed(address)
            pumpDial()
            publish()
            return
        }
        val c = openConn(socket, outgoing = true)
        if (c == null) {
            outgoingFailed(address)
            pumpDial()
            publish()
            return
        }
        addConn(c)
        pumpDial() // следующий из очереди
    }

    /** Не удалось дозвониться: либо пробуем ещё раз (автопереподключение), либо сообщаем пользователю. */
    private fun outgoingFailed(address: String) {
        if (address !in dialing) return
        if (connectThread?.device?.address == address) return // уже идёт новая попытка к нему
        if (conns.any { it.address == address }) return // есть другое живое соединение с ним: ждём его HELLO
        dialing.remove(address)
        if (address in reconnects) {
            scheduleReconnect(address)
        } else {
            notice(R.string.notice_connect_failed, titleFor(address))
        }
    }

    // ---------- Автопереподключение ----------
    // Переподключается только тот, кто подключался первым, — так телефоны не мешают друг другу.

    private fun startReconnect(address: String) {
        cancelReconnect(address)
        reconnects[address] = Reconnect(address)
        scheduleReconnect(address)
    }

    private fun scheduleReconnect(address: String) {
        val r = reconnects[address] ?: return
        if (SystemClock.elapsedRealtime() - r.startedAt > RECONNECT_WINDOW_MS) {
            cancelReconnect(address)
            notice(R.string.notice_reconnect_gave_up, titleFor(address))
            publish()
            return
        }
        val delay = RECONNECT_DELAYS_MS[minOf(r.attempt, RECONNECT_DELAYS_MS.size - 1)]
        h.removeCallbacks(r.task)
        h.postDelayed(r.task, delay)
        publish()
    }

    private fun cancelReconnect(address: String) {
        reconnects.remove(address)?.let { h.removeCallbacks(it.task) }
    }

    private fun cancelAllReconnects() {
        for (a in reconnects.keys.toList()) cancelReconnect(a)
    }

    // ---------- Соединения ----------

    private fun openConn(socket: BluetoothSocket, outgoing: Boolean): Conn? = try {
        Conn(
            socket, outgoing,
            DataInputStream(BufferedInputStream(socket.inputStream)),
            DataOutputStream(BufferedOutputStream(socket.outputStream))
        )
    } catch (e: IOException) {
        closeQuietly(socket)
        null
    }

    private fun addConn(c: Conn) {
        conns.add(c)
        c.start()
        c.send(helloFrame())
        publish()
    }

    private fun helloFrame() = Frame.Hello(Protocol.VERSION, prefs.installId, prefs.userName, prefs.userRole)

    private fun onFrame(c: Conn, f: Frame) {
        if (c !in conns) return
        when (f) {
            is Frame.Hello -> onHello(c, f)
            is Frame.Accept -> if (c.outgoing && c.hello != null && !c.accepted) {
                c.accepted = true
                prefs.trust(c.address)
                if (links[c.address] === c) becomeConnected(c)
            }
            is Frame.Reject -> onReject(c, f)
            is Frame.Msg -> onMsg(c, f.message)
            is Frame.Ack -> if (f.to == null) {
                if (!onVoiceAck(c, f.id)) onAck(c, f.id)
            } else onRelayedAck(c, f)
            is Frame.Ping -> c.send(Frame.Pong)
            is Frame.Pong -> {}
            is Frame.Bye -> {
                val wasLinked = links[c.address] === c && c.accepted
                val title = peerInfo(c).title
                dropConn(c)
                if (c.outgoing) dialing.remove(c.address)
                cancelReconnect(c.address)
                if (wasLinked) notice(R.string.notice_peer_left, title)
                pumpDial()
                publish()
            }
            is Frame.GroupFrame -> onGroupFrame(c, f)
            is Frame.VoiceStart -> onVoiceStart(c, f)
            is Frame.VoiceChunk -> onVoiceChunk(c, f)
            is Frame.VoiceEnd -> onVoiceEnd(c, f)
            is Frame.VoiceNack -> onVoiceNack(c, f)
            is Frame.Unknown -> {}
        }
    }

    private fun onHello(c: Conn, hello: Frame.Hello) {
        if (c.hello != null) return
        if (hello.installId.isBlank() || hello.installId == prefs.installId) {
            dropConn(c)
            publish()
            return
        }
        c.hello = hello
        val knownId = repo.peer(c.address)?.installId.orEmpty() // до upsert, иначе перезапишем
        repo.upsertPeer(c.address, hello.installId, hello.name, hello.role, c.deviceName)

        // Два соединения с одним телефоном: оба нажали «подключиться» одновременно или он
        // переподключился, пока старое соединение ещё не закрылось. Обе стороны выбирают
        // одно и то же по одинаковому правилу, остальное закрывают.
        val dup = conns.firstOrNull { it !== c && it.hello?.installId == hello.installId }
        if (dup != null) {
            val winner = pickWinner(c, dup)
            val loser = if (winner === c) dup else c
            dropConn(loser)
            if (winner !== c) {
                publish()
                return
            }
        }

        val cur = links[c.address]
        if (cur != null && cur !== c) {
            dropConn(cur) // тот же телефон (например, переустановил приложение), старое соединение — заменяем
        }
        if (isFullFor(c.address)) {
            // Пока дозванивались или ждали HELLO, места заняли другие: лимит одновременных соединений
            if (c.outgoing) {
                dialing.remove(c.address)
                dropConn(c)
                notice(R.string.notice_too_many_links, MAX_LINKS)
            } else {
                conns.remove(c)
                c.closeGracefully(Frame.Reject(Frame.REJECT_BUSY))
                notice(R.string.notice_incoming_over_limit, peerInfo(c).title, MAX_LINKS)
            }
            publish()
            return
        }

        links[c.address] = c
        c.linkedAt = SystemClock.elapsedRealtime()
        if (c.outgoing) {
            if (c.accepted) becomeConnected(c) else publish() // ждём ACCEPT
        } else {
            // Адрес берётся из самого Bluetooth-соединения, а не из hello. Но если на этом адресе
            // раньше был другой id установки, это другое приложение/переустановка — спрашиваем.
            val sameInstall = knownId.isBlank() || knownId == hello.installId
            val known = sameInstall && (prefs.isTrusted(c.address) || isBonded(c.address) ||
                c.address in dialing || c.address in reconnects ||
                (recentDial[c.address] ?: 0L) > SystemClock.elapsedRealtime())
            if (known) acceptConn(c) else publish() // IncomingRequest — решает пользователь
        }
    }

    private fun initiatorId(c: Conn): String = if (c.outgoing) prefs.installId else c.hello?.installId.orEmpty()

    private fun pickWinner(a: Conn, b: Conn): Conn {
        val ia = initiatorId(a)
        val ib = initiatorId(b)
        return when {
            ia < ib -> a
            ib < ia -> b
            else -> if (a.seq > b.seq) a else b // один инициатор — новое соединение заменяет старое
        }
    }

    private fun acceptConn(c: Conn) {
        prefs.trust(c.address)
        c.accepted = true
        c.send(Frame.Accept)
        becomeConnected(c)
    }

    private fun becomeConnected(c: Conn) {
        dialing.remove(c.address)
        cancelReconnect(c.address)
        // Встречную попытку к тому же собеседнику не обрываем: если оба нажали «подключиться»
        // одновременно, её сокет у него мог уже стать основным. Она дойдёт до HELLO, и обе
        // стороны выберут одно соединение по общему правилу (pickWinner).
        // Попытки к другим собеседникам тоже идут дальше: соединений может быть несколько.
        publish()
        pumpDial()
        // Группы, в которых этот собеседник состоит: приглашение и состав — раньше сообщений,
        // иначе он не знал бы группу и не подтвердил бы их
        if (c.supportsGroups()) c.hello?.installId?.let { syncGroupsTo(c, it) }
        // всё, что не дошло раньше, отправляем заново (получатель отбросит повторы по id)
        val unacked = repo.unacked(c.address)
        // голосовые уходят своими кадрами (см. ниже), а не как msg
        val queue = unacked.filter { it.kind != Kind.VOICE }.toMutableList()
        // SOS, которому уточнили точку, уже подтверждён, но у собеседника старая точка — шлём ещё раз
        for (id in geoDirty.keys.toList()) {
            val m = repo.message(id)
            if (m == null) geoDirty.remove(id)
            else if (m.peer == c.address && queue.none { it.id == id }) queue += m
        }
        // Сообщения групп, которые этот участник ещё не подтвердил (и SOS с новой точкой для него)
        val iid = c.hello?.installId
        if (iid != null && c.supportsGroups()) {
            for (m in repo.groupUnacked(iid)) if (queue.none { it.id == m.id }) queue += m
            for ((id, owed) in groupGeoOwed.toMap()) {
                if (iid !in owed.ids || queue.any { it.id == id }) continue
                val m = repo.message(id)
                if (m == null) groupGeoOwed.remove(id) else queue += m
            }
        }
        // тревога уходит первой, остальное — в прежнем порядке (сортировка устойчивая)
        for (m in queue.sortedBy { if (it.kind == Kind.SOS) 0 else 1 }) sendWire(c, m)
        // голосовые сообщения, которые собеседник не подтвердил (обрыв во время передачи): целиком заново
        for (o in voiceOut.values.toList()) if (o.peer == c.address) pushVoiceOut(c, o, null)
        // то же для сообщений, которых в памяти уже нет (приложение перезапускали, или их вытеснили): звук берём из файла
        if (c.supportsVoice()) {
            for (m in unacked) {
                if (m.kind != Kind.VOICE || m.id in voiceOut) continue
                val chunks = VoiceStore.chunksForResend(ctx, m.id) ?: continue
                if (chunks.size > Protocol.MAX_VOICE_CHUNKS || chunks.sumOf { it.size } > Protocol.MAX_VOICE_BYTES) continue
                val out = VoiceOut(m.id, c.address, VoiceStore.RESEND_FRAMES_PER_CHUNK * AmrWb.FRAME_MS, m.sentAt, chunks)
                voiceOut[m.id] = out
                while (voiceOut.size > MAX_VOICE_OUT) voiceOut.remove(voiceOut.keys.first())
                pushVoiceOut(c, out, null)
            }
        }
    }

    private fun onReject(c: Conn, f: Frame.Reject) {
        val title = peerInfo(c).title
        dropConn(c)
        if (c.outgoing) {
            dialing.remove(c.address)
            cancelReconnect(c.address)
            notice(
                when (f.reason) {
                    Frame.REJECT_BUSY -> R.string.notice_peer_busy
                    Frame.REJECT_TIMEOUT -> R.string.notice_peer_no_answer
                    else -> R.string.notice_peer_rejected
                },
                title
            )
        }
        pumpDial()
        publish()
    }

    private fun onAck(c: Conn, id: String) {
        if (repo.upgradeStatus(id, c.address, Status.DELIVERED)) {
            emitMessages(c.address)
            return
        }
        // Не личное сообщение — возможно, групповое: подтверждение считаем за этого участника
        val iid = c.hello?.installId ?: return
        if (repo.setGroupDelivery(id, iid, Status.DELIVERED)) updateGroupStatus(id)
    }

    private fun onMsg(c: Conn, w: WireMessage) {
        if (links[c.address] !== c || !c.accepted) return
        if (!w.isDirect) {
            onRelayedMsg(c, w)
            return
        }
        if (w.groupId != null) {
            onGroupMsg(c, w, w.groupId)
            return
        }
        storePersonalIncoming(w, c.address, peerInfo(c)) { c.send(Frame.Ack(w.id)) }
    }

    /**
     * Личное сообщение: сохраняем в чат собеседника [address] и подтверждаем через [ack]
     * (и повторы тоже: значит, прошлый ACK потерялся).
     */
    private fun storePersonalIncoming(w: WireMessage, address: String, peer: PeerInfo, via: Int = 0, ack: () -> Unit) {
        val m = ChatMessage(
            id = w.id, peer = address, fromMe = false, kind = w.kind, text = w.text, geo = w.geo,
            sentAt = w.sentAt, receivedAt = System.currentTimeMillis(), status = Status.RECEIVED, via = via
        )
        val isNew = repo.insertIncoming(m)
        ack()
        if (!isNew) {
            // Тот же SOS, но с более свежей точкой: обновляем сообщение, а не создаём второе
            val g = w.geo
            if (w.kind == Kind.SOS && g != null && repo.refreshGeo(w.id, address, false, g)) {
                emitMessages(address)
                val upd = repo.message(w.id)
                if (upd != null) main.post { listeners.forEach { it.onMessageUpdated(upd, peer) } }
            }
            return
        }
        if (visibleChat != address) repo.incUnread(address)
        emitMessages(address)
        main.post { listeners.forEach { it.onIncoming(m, peer) } }
    }

    /**
     * Сообщение группы от участника. Чужую или неизвестную нам группу не подтверждаем и не сохраняем:
     * отправитель повторит позже (когда мы получим состав группы).
     */
    private fun onGroupMsg(c: Conn, w: WireMessage, groupId: String) {
        val senderId = c.hello?.installId ?: return
        val g = repo.group(groupId) ?: return
        if (g.members.none { it.installId == senderId }) return // пишет не участник
        storeGroupIncoming(w, groupId, senderId, peerInfo(c)) { c.send(Frame.Ack(w.id)) }
    }

    /** Сохранить сообщение группы от участника [senderId] и подтвердить через [ack]. Проверки участника — на вызывающем. */
    private fun storeGroupIncoming(
        w: WireMessage, groupId: String, senderId: String, peer: PeerInfo, via: Int = 0, ack: () -> Unit
    ) {
        val key = groupChatKey(groupId)
        val m = ChatMessage(
            id = w.id, peer = key, fromMe = false, kind = w.kind, text = w.text, geo = w.geo,
            sentAt = w.sentAt, receivedAt = System.currentTimeMillis(), status = Status.RECEIVED,
            groupId = groupId, senderId = senderId, via = via
        )
        val isNew = repo.insertIncoming(m)
        ack()
        if (!isNew) {
            val geo = w.geo
            if (w.kind == Kind.SOS && geo != null && repo.refreshGeo(w.id, key, false, geo)) {
                emitMessages(key)
                val upd = repo.message(w.id)
                if (upd != null) main.post { listeners.forEach { it.onMessageUpdated(upd, peer) } }
            }
            return
        }
        if (visibleChat != key) repo.incGroupUnread(groupId)
        emitMessages(key)
        main.post { listeners.forEach { it.onIncoming(m, peer) } }
    }

    // ---------- Пересылка по цепочке ----------
    // Чужое сообщение (dest не я) в моём чате не появляется: оно только проходит через телефон.
    // Правила — в MeshRules (там же они проверены тестами). Здесь только сеть и база.
    // Очереди на пересылку нет: нет соседей, значит кадр не уходит, автор повторит сам (нет ACK).

    private fun onRelayedMsg(c: Conn, w: WireMessage) {
        val sender = c.hello?.installId?.takeIf { it.isNotBlank() } ?: return
        if (!c.supportsMesh()) return
        val plan = MeshRules.planInbound(
            w, prefs.installId, sender,
            isMyGroup = { repo.group(it) != null },
            relayForeignGroups = prefs.relayForeignGroups,
            relayEnabled = prefs.relayEnabled
        )
        if (plan.drop != MeshRules.Drop.NONE) return
        if (plan.deliver) deliverRelayed(c, w)
        val copy = plan.forward ?: return
        val origin = w.origin ?: return
        // Повтор (тот же кадр пришёл вторым путём или вернулся) второй раз не пересылаем
        if (!seenRelayed.markNew(origin, MeshRules.seenKey(w))) return
        forwardToOthers(c, copy)
    }

    /** Отправить копию всем принятым соединениям, кроме того, откуда кадр пришёл, и узлов из hops. */
    private fun forwardToOthers(from: Conn, copy: WireMessage) {
        val neighbors = links.values.filter { it.accepted && it !== from && it.supportsMesh() }
        val ids = MeshRules.forwardTargets(copy, neighbors.mapNotNull { it.hello?.installId }, from.hello?.installId.orEmpty()).toSet()
        var sent = 0
        for (n in neighbors) {
            if (n.hello?.installId in ids) {
                n.send(Frame.Msg(copy))
                sent++
            }
        }
        if (sent > 0) prefs.countRelayed()
    }

    /** Сообщение адресовано мне (лично или в мою группу): в чат и ACK обратно по пути hops. */
    private fun deliverRelayed(c: Conn, w: WireMessage) {
        val origin = w.origin ?: return
        val ack = { c.send(MeshRules.relayedAck(w, prefs.installId)) }
        val via = MeshRules.viaCount(w) // сколько посредников было между автором и мной: для пометки «через N»
        val gid = w.groupId
        if (gid != null) {
            val g = repo.group(gid) ?: return
            val author = g.members.firstOrNull { it.installId == origin } ?: return // пишет не участник
            val rec = repo.peerByInstallId(origin)
            val peer = PeerInfo(rec?.address.orEmpty(), rec?.name?.ifBlank { author.name } ?: author.name, rec?.role.orEmpty(), rec?.deviceName.orEmpty())
            storeGroupIncoming(w, gid, origin, peer, via, ack)
        } else {
            // Личный чат ведётся по адресу Bluetooth. Автора, с которым мы не знакомы, показать некуда:
            // не сохраняем и не подтверждаем, автор повторит позже
            val rec = repo.peerByInstallId(origin) ?: return
            storePersonalIncoming(w, rec.address, PeerInfo(rec.address, rec.name, rec.role, rec.deviceName), via, ack)
        }
    }

    /** ACK, пришедший по цепочке: либо передаём дальше по route, либо он наш и отмечаем доставку. */
    private fun onRelayedAck(c: Conn, f: Frame.Ack) {
        if (links[c.address] !== c || !c.accepted || !c.supportsMesh()) return
        val plan = MeshRules.planAck(f, prefs.installId, relayEnabled = prefs.relayEnabled)
        if (plan.deliver) {
            f.from?.let { markRelayedDelivered(f.id, it) }
            return
        }
        val fwd = plan.forward ?: return
        val next = links.values.firstOrNull { it.accepted && it.supportsMesh() && it.hello?.installId == plan.nextHop } ?: return
        next.send(fwd) // звена нет: ACK теряется, автор повторит сообщение
    }

    /** Подтверждение от [from] для моего сообщения [id]. Личное: от того, кому оно адресовано. Группа: по участнику. */
    private fun markRelayedDelivered(id: String, from: String) {
        val m = repo.message(id) ?: return
        if (!m.fromMe) return
        if (m.groupId != null) {
            if (repo.setGroupDelivery(id, from, Status.DELIVERED)) updateGroupStatus(id)
        } else if (repo.peer(m.peer)?.installId == from && repo.upgradeStatus(id, m.peer, Status.DELIVERED)) {
            emitMessages(m.peer)
        }
    }

    // ---------- Управление группой ----------
    // Нет «хозяина» группы: приглашает тот, кто её создал, дальше состав синхронизируется кадрами
    // GROUP_MEMBERS (применяется, если rev не меньше известного). Вступление — без подтверждения:
    // приглашать может только собеседник, чьё подключение вы уже приняли; выйти можно в любой момент.

    private fun doCreateGroup(name: String, memberAddresses: List<String>): String? {
        val title = name.cleanLine(Protocol.MAX_NAME)
        if (title.isEmpty()) return null
        val members = LinkedHashMap<String, GroupMember>()
        members[prefs.installId] = GroupMember(prefs.installId, prefs.userName)
        val invited = ArrayList<String>() // адреса приглашённых
        for (a in memberAddresses) {
            if (members.size >= Protocol.MAX_GROUP_MEMBERS) break
            val p = repo.peer(a) ?: continue
            if (p.installId.isBlank() || p.installId == prefs.installId || p.installId in members) continue
            members[p.installId] = GroupMember(p.installId, p.name.ifBlank { p.deviceName })
            invited += a
        }
        if (invited.isEmpty()) return null
        val id = UUID.randomUUID().toString()
        repo.saveGroup(id, title)
        repo.setMembers(id, 1L, members.values.toList())
        val g = repo.group(id) ?: return null
        for (a in invited) {
            val c = links[a]
            if (c != null && c.accepted) {
                if (c.supportsGroups()) c.hello?.installId?.let { syncGroupsTo(c, it) }
            } else if (started && bluetoothReady()) {
                doConnect(a, keepOthers = true) // приглашение уйдёт в becomeConnected
            }
        }
        emitMessages(g.chatKey)
        return id
    }

    private fun doLeaveGroup(groupId: String) {
        val g = repo.group(groupId) ?: return
        prefs.markLeftGroup(groupId)
        for (c in groupLinks(g.members.map { it.installId })) c.send(Frame.GroupLeave(groupId, prefs.installId))
        repo.deleteGroup(groupId)
        emitMessages(g.chatKey)
    }

    /** Отправить собеседнику приглашение и состав всех групп, где он числится участником. */
    private fun syncGroupsTo(c: Conn, installId: String) {
        for (g in repo.groups()) {
            if (g.members.none { it.installId == installId }) continue
            c.send(Frame.GroupInvite(g.id, g.name, g.members))
            c.send(Frame.GroupMembers(g.id, g.rev, g.members))
        }
    }

    /** Разослать состав группы тем участникам, что на связи (кроме [except]). */
    private fun broadcastMembers(g: GroupInfo, except: Conn?) {
        for (c in groupLinks(g.members.map { it.installId })) {
            if (c !== except) c.send(Frame.GroupMembers(g.id, g.rev, g.members))
        }
    }

    private fun onGroupFrame(c: Conn, f: Frame.GroupFrame) {
        if (links[c.address] !== c || !c.accepted) return
        val sender = c.hello?.installId?.takeIf { it.isNotBlank() } ?: return
        if (!c.supportsGroups()) return
        // Мы уже вышли из этой группы, а собеседник ещё считает нас участником: повторяем «я вышел»
        if (f !is Frame.GroupLeave && prefs.hasLeftGroup(f.groupId)) {
            c.send(Frame.GroupLeave(f.groupId, prefs.installId))
            return
        }
        when (f) {
            is Frame.GroupInvite -> onGroupInvite(c, sender, f)
            is Frame.GroupJoin -> onGroupJoin(c, sender, f)
            is Frame.GroupLeave -> onGroupLeave(c, sender, f)
            is Frame.GroupMembers -> onGroupMembers(c, sender, f)
        }
    }

    private fun onGroupInvite(c: Conn, sender: String, f: Frame.GroupInvite) {
        if (repo.group(f.groupId) != null) return // группа уже есть — состав придёт кадром GROUP_MEMBERS
        // Приглашает только участник, и мы должны быть в списке — иначе это не про нас
        if (f.members.none { it.installId == sender } || f.members.none { it.installId == prefs.installId }) return
        val name = f.name.ifBlank { ctx.getString(R.string.group_default_name) }
        repo.saveGroup(f.groupId, name)
        repo.setMembers(f.groupId, 0L, f.members)
        c.send(Frame.GroupJoin(f.groupId, GroupMember(prefs.installId, prefs.userName)))
        emitMessages(groupChatKey(f.groupId))
        notice(R.string.notice_added_to_group, peerInfo(c).title, name)
    }

    /** Приглашённый подтвердил вступление: отвечаем актуальным составом (и обновляем его имя, если оно изменилось). */
    private fun onGroupJoin(c: Conn, sender: String, f: Frame.GroupJoin) {
        if (f.member.installId != sender) return
        val g = repo.group(f.groupId) ?: return
        val known = g.members.firstOrNull { it.installId == sender } ?: return // приглашать мог только участник
        if (f.member.name.isNotBlank() && f.member.name != known.name) {
            repo.addMember(g.id, f.member)
            val fresh = repo.group(g.id) ?: return
            broadcastMembers(fresh, except = null) // c тоже участник — получит новый состав вместе со всеми
            emitMessages(g.chatKey)
        } else {
            c.send(Frame.GroupMembers(g.id, g.rev, g.members))
        }
    }

    /** Выйти можно только за себя: чужой GROUP_LEAVE (исключение) не принимаем. */
    private fun onGroupLeave(c: Conn, sender: String, f: Frame.GroupLeave) {
        if (f.installId != sender) return
        val g = repo.group(f.groupId) ?: return
        val who = g.members.firstOrNull { it.installId == sender } ?: return
        repo.removeMember(g.id, sender)
        val fresh = repo.group(g.id) ?: return
        broadcastMembers(fresh, except = c)
        emitMessages(g.chatKey)
        notice(R.string.notice_member_left_group, who.name.ifBlank { peerInfo(c).title }, g.name)
    }

    private fun onGroupMembers(c: Conn, sender: String, f: Frame.GroupMembers) {
        val g = repo.group(f.groupId) ?: return
        if (g.members.none { it.installId == sender }) return // состав шлёт только участник
        if (f.rev < g.rev) return
        // Себя из списка не убираем: исключения нет, а чужой устаревший список не должен нас «выписать»
        var list = f.members
        if (list.none { it.installId == prefs.installId }) {
            list = list.take(Protocol.MAX_GROUP_MEMBERS - 1) + GroupMember(prefs.installId, prefs.userName)
        }
        if (f.rev == g.rev && list == g.members) return // ничего нового
        if (repo.setMembers(g.id, f.rev, list)) emitMessages(g.chatKey)
    }

    /** Соединение оборвалось само (не мы его закрыли). */
    private fun onConnClosed(c: Conn) {
        if (!conns.remove(c)) return
        c.closeNow()
        val wasLinked = links[c.address] === c
        if (wasLinked) links.remove(c.address)
        if (wasLinked && c.hello != null) {
            val title = peerInfo(c).title
            when {
                c.accepted && c.outgoing -> startReconnect(c.address)
                c.accepted -> notice(R.string.notice_link_lost, title) // он переподключится сам
                c.outgoing -> {
                    dialing.remove(c.address)
                    notice(R.string.notice_connect_failed, title)
                }
                else -> {} // просивший подключения передумал
            }
        } else if (c.outgoing) {
            outgoingFailed(c.address)
        }
        pumpDial()
        publish()
    }

    /** Отключить одного собеседника; [address] = null — того, кого сейчас показывает [state]. */
    private fun doDisconnect(address: String?) {
        val target = address ?: computeState().peerAddress ?: return
        resumeTargets.remove(target)
        cancelReconnect(target)
        dialing.remove(target)
        val t = connectThread
        if (t != null && t.device.address == target) {
            t.cancel()
            connectThread = null
        }
        links.remove(target)?.let {
            conns.remove(it)
            it.closeGracefully(farewell(it))
        }
        for (c in conns.filter { it.address == target }) { // ещё не приславшие HELLO
            conns.remove(c)
            c.closeGracefully(Frame.Bye)
        }
        pumpDial()
        publish()
    }

    private fun doDisconnectAll() {
        resumeTargets.clear()
        cancelAllReconnects()
        connectThread?.cancel()
        connectThread = null
        dialing.clear()
        leaveAll()
        publish()
    }

    /** Закрыть все соединения, сообщив каждому собеседнику причину. */
    private fun leaveAll() {
        for (c in conns.toList()) c.closeGracefully(farewell(c))
        conns.clear()
        links.clear()
    }

    /** Последний кадр при закрытии нами: отказ — тем, чей запрос мы не приняли, BYE — остальным. */
    private fun farewell(c: Conn): Frame =
        if (c.outgoing || c.accepted || c.hello == null) Frame.Bye else Frame.Reject(Frame.REJECT_DECLINED)

    /** Запрос незнакомого, который ждёт решения пользователя (самый ранний). */
    private fun pendingRequest(): Conn? = links.values.firstOrNull { !it.outgoing && !it.accepted && it.hello != null }

    private fun doAcceptIncoming() {
        val c = pendingRequest() ?: return
        acceptConn(c)
    }

    private fun doRejectIncoming(reason: String) {
        val c = pendingRequest() ?: return
        rejectConn(c, reason)
    }

    private fun rejectConn(c: Conn, reason: String) {
        if (c.outgoing || c.accepted) return
        if (links[c.address] === c) links.remove(c.address)
        conns.remove(c)
        c.closeGracefully(Frame.Reject(reason))
        publish()
    }

    /** Закрыть без событий и без переподключения. */
    private fun dropConn(c: Conn) {
        conns.remove(c)
        if (links[c.address] === c) links.remove(c.address)
        c.closeNow()
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        // Страховка: очередь дозвона не должна застревать — если сейчас никому не звоним,
        // следующий из очереди стартует (иначе состояние Connecting висело бы вечно).
        if (dialing.isNotEmpty()) pumpDial()
        // Ping, таймаут тишины и таймаут HELLO — для каждого соединения отдельно
        for (c in conns.toList()) {
            when {
                c.hello == null && now - c.createdAt > HELLO_TIMEOUT_MS -> onConnClosed(c)
                now - c.lastRx > DEAD_TIMEOUT_MS -> onConnClosed(c)
                now - c.lastTx > PING_INTERVAL_MS -> c.send(Frame.Ping)
            }
        }
        voiceTick(now)
        // Ожидание решения по каждому запросу — тоже отдельно
        for (c in links.values.toList()) {
            if (c.accepted || links[c.address] !== c) continue
            if (!c.outgoing && now - c.linkedAt > REQUEST_TIMEOUT_MS) {
                rejectConn(c, Frame.REJECT_TIMEOUT)
            } else if (c.outgoing && now - c.linkedAt > REQUEST_TIMEOUT_MS + 15_000) {
                val title = peerInfo(c).title
                dropConn(c)
                dialing.remove(c.address)
                cancelReconnect(c.address)
                notice(R.string.notice_peer_no_answer, title)
                publish()
            }
        }
    }

    // ---------- Сообщения ----------

    private fun doRefreshGeo(peer: String, id: String, geo: GeoPoint) {
        if (!geo.isValid() || !repo.refreshGeo(id, peer, true, geo)) return
        geoDirty[id] = geo.fixTime
        emitMessages(peer)
        // Если SOS ещё в очереди, он уйдёт уже с новой точкой; если связь есть — шлём повтор с тем же id
        val c = links[peer]
        if (c != null && c.accepted) repo.message(id)?.let { sendWire(c, it) }
    }

    /** Групповой SOS: новая точка уходит подключённым участникам сразу, остальным — когда подключатся. */
    private fun doRefreshGroupGeo(groupId: String, id: String, geo: GeoPoint) {
        val key = groupChatKey(groupId)
        if (!geo.isValid() || !repo.refreshGeo(id, key, true, geo)) return
        val g = repo.group(groupId) ?: return
        val owed = g.members.map { it.installId }.filter { it != prefs.installId }.toMutableSet()
        groupGeoOwed[id] = GeoOwed(geo.fixTime, owed)
        emitMessages(key)
        val m = repo.message(id) ?: return
        for (c in groupLinks(owed)) sendWire(c, m)
    }

    private fun enqueue(
        peer: String, kind: Kind, text: String, geo: GeoPoint?,
        id: String = UUID.randomUUID().toString(), groupId: String? = null
    ) {
        val now = System.currentTimeMillis()
        val body = text.take(Protocol.MAX_TEXT)
        // Получатель отбрасывает пустой текст и гео без точки (WireMessage.fromJson) и не шлёт на них ACK —
        // такое сообщение вечно висело бы «отправлено» и уходило заново при каждом подключении.
        if (kind == Kind.TEXT && body.isBlank()) return
        if (kind == Kind.GEO && geo?.isValid() != true) return
        val m = ChatMessage(
            id = id, peer = peer, fromMe = true, kind = kind,
            text = body, geo = geo, sentAt = now, receivedAt = now, status = Status.PENDING,
            groupId = groupId
        )
        if (groupId != null) {
            enqueueGroup(m, groupId)
            return
        }
        repo.insertOutgoing(m)
        emitMessages(peer)
        val c = links[peer]
        if (c != null && c.accepted) sendWire(c, m)
    }

    /**
     * Сообщение группы: сохраняем, заводим строку доставки на каждого участника (кроме себя) и шлём
     * тем, кто подключён. Остальные получат его при подключении (см. becomeConnected).
     */
    private fun enqueueGroup(m: ChatMessage, groupId: String) {
        val g = repo.group(groupId) ?: return
        val recipients = g.members.map { it.installId }.filter { it != prefs.installId }
        repo.insertOutgoing(m)
        repo.initGroupDelivery(m.id, recipients)
        emitMessages(m.peer)
        for (c in groupLinks(recipients)) sendWire(c, m)
    }

    /** Принятые соединения с указанными участниками (только те, чья версия протокола знает группы). */
    private fun groupLinks(installIds: Collection<String>): List<Conn> =
        links.values.filter { it.accepted && it.supportsGroups() && it.hello?.installId in installIds }

    /**
     * Общий статус группового сообщения из доставки по участникам: все подтвердили — «доставлено»,
     * хоть кому-то записано в канал — «отправлено», иначе «ожидает».
     */
    private fun updateGroupStatus(id: String) {
        val m = repo.message(id) ?: return
        val per = repo.groupDelivery(id).values
        if (per.isNotEmpty()) {
            val total = when {
                per.all { it == Status.DELIVERED } -> Status.DELIVERED
                per.any { it.code >= Status.SENT.code } -> Status.SENT
                else -> Status.PENDING
            }
            repo.upgradeStatus(id, m.peer, total)
        }
        emitMessages(m.peer)
    }

    private fun sendWire(c: Conn, m: ChatMessage) {
        // Сообщение с точкой могло ждать связи в очереди: возраст точки считаем на момент реальной
        // отправки (часы отправителя, как и время замера), а не на момент нажатия кнопки.
        val wire = if (m.geo != null) m.toWire().copy(sentAt = maxOf(m.sentAt, System.currentTimeMillis())) else m.toWire()
        // onWritten — именованным аргументом: после ttlMs завершающая лямбда уже не подставилась бы сама
        c.send(Frame.Msg(wire), onWritten = {
            if (m.groupId != null) {
                // Группа: «отправлено» и «новая точка ушла» отмечаем отдельно по этому участнику
                val iid = c.hello?.installId
                if (iid != null) {
                    groupGeoOwed[m.id]?.let { o ->
                        if (o.fixTime == m.geo?.fixTime) {
                            o.ids.remove(iid)
                            if (o.ids.isEmpty()) groupGeoOwed.remove(m.id)
                        }
                    }
                    if (repo.setGroupDelivery(m.id, iid, Status.SENT)) updateGroupStatus(m.id)
                }
            } else {
                if (geoDirty[m.id] == m.geo?.fixTime) geoDirty.remove(m.id) // новая точка ушла в канал
                if (repo.upgradeStatus(m.id, c.address, Status.SENT)) emitMessages(c.address)
            }
        })
    }

    // ---------- Голос ----------

    private fun newVoiceId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    /**
     * Отправить голосовое сообщение целиком (режим «сообщение»): [chunks] — куски с рекордера по порядку,
     * [chunkMs] — длина куска. Возвращает id сообщения, либо null, если оно негодно (пусто, длиннее минуты,
     * слишком большое, чат группы). Сообщение держится в памяти, пока собеседник его не подтвердит; если связи
     * нет или она оборвётся, уйдёт заново при подключении. Результат — onVoiceDelivered / onVoiceFailed.
     */
    fun sendVoiceMessage(peer: String, chunks: List<ByteArray>, chunkMs: Int): String? {
        if (groupIdFromKey(peer) != null) return null
        if (chunks.isEmpty() || chunks.size > Protocol.MAX_VOICE_CHUNKS) return null
        if (chunkMs !in 10..200 || chunks.size.toLong() * chunkMs > Protocol.MAX_VOICE_MS) return null
        if (chunks.any { it.isEmpty() || it.size > Protocol.MAX_VOICE_CHUNK_BYTES }) return null
        if (chunks.sumOf { it.size } > Protocol.MAX_VOICE_BYTES) return null
        val out = VoiceOut(newVoiceId(), peer, chunkMs, System.currentTimeMillis(), chunks.map { it.copyOf() })
        h.post {
            if (storeOutgoingVoice(out)) doSendVoiceMessage(out)
        }
        return out.id
    }

    /**
     * Своё голосовое сообщение в историю: звук в файл, строка в чат (kind = voice, text = длина в мс), статус «ожидает».
     * false — звук сохранить не удалось: сообщение не отправляем, чтобы в чате не было «призрака» без звука.
     */
    private fun storeOutgoingVoice(out: VoiceOut): Boolean {
        if (!VoiceStore.save(ctx, out.id, out.chunks)) {
            notice(R.string.voice_save_failed)
            return false
        }
        val ms = VoiceStore.durationMs(out.chunks)
        repo.insertOutgoing(
            ChatMessage(
                id = out.id, peer = out.peer, fromMe = true, kind = Kind.VOICE, text = ms.toString(), geo = null,
                sentAt = out.sentAt, receivedAt = out.sentAt, status = Status.PENDING
            )
        )
        emitMessages(out.peer)
        return true
    }

    /**
     * Начать рацию: куски пойдут сразу по мере записи через [LiveVoice.push]. Куски, не успевшие уйти за
     * [LIVE_TTL_MS], выбрасываются: рация не копит задержку, а получатель видит пропуск по номерам.
     */
    fun startLive(peer: String, chunkMs: Int): LiveVoice {
        val v = LiveVoice(newVoiceId(), peer)
        h.post { doStartLive(v, peer, chunkMs.coerceIn(10, 200)) }
        return v
    }

    private fun emitVoice(block: (Listener) -> Unit) {
        main.post { listeners.forEach(block) }
    }

    private fun voiceReady(c: Conn) = links[c.address] === c && c.accepted

    private fun doSendVoiceMessage(out: VoiceOut) {
        voiceOut[out.id] = out
        while (voiceOut.size > MAX_VOICE_OUT) voiceOut.remove(voiceOut.keys.first())
        val c = links[out.peer]
        if (c != null && c.accepted) pushVoiceOut(c, out, null)
    }

    /** Кадры сообщения в канал: всё ([only] = null) или только недостающие куски и конец. */
    private fun pushVoiceOut(c: Conn, out: VoiceOut, only: Set<Int>?) {
        if (!c.supportsVoice()) {
            voiceOut.remove(out.id)
            notice(R.string.voice_peer_unsupported)
            val peer = peerInfo(c)
            emitVoice { it.onVoiceFailed(peer, out.id, false) }
            return
        }
        if (only == null) {
            c.send(Frame.VoiceStart(out.id, false, Protocol.VOICE_CODEC_AMR_WB, out.chunkMs, out.sentAt))
        }
        for ((i, d) in out.chunks.withIndex()) {
            if (only == null || i in only) c.send(Frame.VoiceChunk(out.id, i, d))
        }
        c.send(Frame.VoiceEnd(out.id, out.chunks.size), onWritten = {
            // конец ушёл в канал: «отправлено» (две галочки появятся по подтверждению собеседника)
            if (repo.upgradeStatus(out.id, out.peer, Status.SENT)) emitMessages(out.peer)
        })
    }

    private fun doStartLive(v: LiveVoice, peer: String, chunkMs: Int) {
        val c = links[peer]
        if (c == null || !c.accepted) {
            v.isActive = false
            notice(R.string.voice_no_link)
            return
        }
        if (!c.supportsVoice()) {
            v.isActive = false
            notice(R.string.voice_peer_unsupported)
            return
        }
        c.send(Frame.VoiceStart(v.id, true, Protocol.VOICE_CODEC_AMR_WB, chunkMs, System.currentTimeMillis()))
    }

    private fun sendLiveChunk(v: LiveVoice, peer: String, n: Int, chunk: ByteArray) {
        val c = links[peer]
        if (c == null || !c.accepted) {
            v.isActive = false // связь пропала: рация кончилась
            return
        }
        c.send(Frame.VoiceChunk(v.id, n, chunk), ttlMs = LIVE_TTL_MS)
    }

    /** Подтверждение своего голосового сообщения. false — это ACK не на голосовое. */
    private fun onVoiceAck(c: Conn, id: String): Boolean {
        val out = voiceOut[id] ?: return false
        if (out.peer != c.address) return false
        voiceOut.remove(id)
        if (repo.upgradeStatus(id, c.address, Status.DELIVERED)) emitMessages(c.address)
        val peer = peerInfo(c)
        emitVoice { it.onVoiceDelivered(peer, id) }
        return true
    }

    private fun onVoiceStart(c: Conn, f: Frame.VoiceStart) {
        if (!voiceReady(c)) return
        if (f.codec != Protocol.VOICE_CODEC_AMR_WB) return // формат, которого мы не знаем
        if (!f.live && f.id in voiceDone) { // уже принято, а наш ACK потерялся
            c.send(Frame.Ack(f.id))
            return
        }
        // рация у собеседника одна: новая начинается — старая закончилась
        if (f.live) for (old in c.voiceIn.values.filter { it.live }) endLiveIn(c, old, 0)
        if (c.voiceIn.size >= MAX_VOICE_IN && !c.voiceIn.containsKey(f.id)) {
            c.voiceIn.values.firstOrNull { !it.live }?.let { failVoiceIn(c, it) }
        }
        c.voiceIn[f.id] = VoiceIn(f.id, f.live, f.chunkMs, f.sentAt, SystemClock.elapsedRealtime())
        val peer = peerInfo(c)
        emitVoice { it.onVoiceStarted(peer, f.id, f.live, f.chunkMs) }
    }

    private fun onVoiceChunk(c: Conn, f: Frame.VoiceChunk) {
        if (!voiceReady(c)) return
        val s = c.voiceIn[f.id] ?: return // начало не видели или сессия закрыта
        s.lastRx = SystemClock.elapsedRealtime()
        val gap = s.gap
        if (gap != null) {
            val lost = gap.accept(f.seq)
            if (lost < 0) return // опоздал или повтор: рация назад не отматывается
            val peer = peerInfo(c)
            emitVoice { it.onVoiceChunk(peer, f.id, f.seq, lost, f.data) }
        } else if (s.asm?.add(f.seq, f.data) == false) {
            failVoiceIn(c, s)
        }
    }

    private fun onVoiceEnd(c: Conn, f: Frame.VoiceEnd) {
        if (!voiceReady(c)) return
        val s = c.voiceIn[f.id]
        if (s == null) {
            if (f.id in voiceDone) c.send(Frame.Ack(f.id))
            return
        }
        if (s.live) {
            endLiveIn(c, s, s.gap?.lostTail(f.count) ?: 0)
            return
        }
        val asm = s.asm ?: return
        if (asm.isComplete(f.count)) {
            c.voiceIn.remove(s.id)
            voiceDone.add(s.id)
            while (voiceDone.size > MAX_VOICE_DONE) voiceDone.remove(voiceDone.first())
            c.send(Frame.Ack(s.id))
            val chunks = asm.chunks(f.count)
            val peer = peerInfo(c)
            storeIncomingVoice(c, s, chunks, peer)
            emitVoice { it.onVoiceMessage(peer, s.id, s.chunkMs, s.sentAt, chunks) }
        } else if (f.count in 1..Protocol.MAX_VOICE_CHUNKS && s.nacks < MAX_VOICE_NACKS) {
            s.nacks++
            s.lastRx = SystemClock.elapsedRealtime()
            c.send(Frame.VoiceNack(s.id, asm.missing(f.count)))
        } else {
            failVoiceIn(c, s)
        }
    }

    /**
     * Принятое голосовое сообщение в историю: звук в файл, строка в чат, счётчик непрочитанных и уведомление
     * (как у текста). Повтор (id уже есть в базе) второй раз не показывается. ACK к этому моменту уже ушёл.
     */
    private fun storeIncomingVoice(c: Conn, s: VoiceIn, chunks: List<ByteArray>, peer: PeerInfo) {
        val ms = VoiceStore.durationMs(chunks)
        if (ms <= 0 || !VoiceStore.save(ctx, s.id, chunks)) {
            notice(R.string.voice_save_failed)
            return
        }
        val m = ChatMessage(
            id = s.id, peer = c.address, fromMe = false, kind = Kind.VOICE, text = ms.toString(), geo = null,
            sentAt = s.sentAt, receivedAt = System.currentTimeMillis(), status = Status.RECEIVED
        )
        if (!repo.insertIncoming(m)) return
        if (visibleChat != c.address) repo.incUnread(c.address)
        emitMessages(c.address)
        main.post { listeners.forEach { it.onIncoming(m, peer) } }
    }

    /** Собеседник просит досылать недостающие куски нашего сообщения. */
    private fun onVoiceNack(c: Conn, f: Frame.VoiceNack) {
        if (!voiceReady(c)) return
        val out = voiceOut[f.id] ?: return
        if (out.peer != c.address) return
        if (out.resends >= MAX_VOICE_NACKS) {
            voiceOut.remove(out.id)
            val peer = peerInfo(c)
            emitVoice { it.onVoiceFailed(peer, out.id, false) }
            return
        }
        out.resends++
        val only = f.missing.filter { it < out.chunks.size }.toSet()
        if (only.isNotEmpty()) pushVoiceOut(c, out, only)
    }

    private fun endLiveIn(c: Conn, s: VoiceIn, lostTail: Int) {
        c.voiceIn.remove(s.id)
        val peer = peerInfo(c)
        emitVoice { it.onVoiceLiveEnded(peer, s.id, lostTail) }
    }

    private fun failVoiceIn(c: Conn, s: VoiceIn) {
        c.voiceIn.remove(s.id)
        val peer = peerInfo(c)
        emitVoice { it.onVoiceFailed(peer, s.id, true) }
    }

    /** Соединение закрывается: незаконченные входящие передачи заканчиваем (рация) или бросаем (сообщение). */
    private fun closeVoiceIn(c: Conn) {
        if (c.voiceIn.isEmpty()) return
        for (s in c.voiceIn.values.toList()) if (s.live) endLiveIn(c, s, 0) else failVoiceIn(c, s)
    }

    /** Молчащие передачи: рация, у которой пропал конец, и сообщение, которое не дошло. */
    private fun voiceTick(now: Long) {
        for (c in links.values.toList()) {
            for (s in c.voiceIn.values.toList()) {
                val idle = now - s.lastRx
                if (s.live && idle > LIVE_IDLE_MS) endLiveIn(c, s, 0)
                else if (!s.live && idle > VOICE_IDLE_MS) failVoiceIn(c, s)
            }
        }
    }

    // ---------- Публикация состояния ----------

    private fun computeState(): State {
        val a = adapter ?: return State.Off(OffReason.NO_ADAPTER)
        if (!started) return State.Off(OffReason.STOPPED)
        if (!hasConnectPermission()) return State.Off(OffReason.NO_PERMISSION)
        if (!a.isEnabled) return State.Off(OffReason.BLUETOOTH_OFF)
        // Экран пока умеет показывать одно соединение: выбираем главное. Сначала запрос,
        // который ждёт решения; затем собеседник открытого чата; затем любое принятое.
        val c = pendingRequest()
            ?: visibleChat?.let { links[it] }
            ?: links.values.firstOrNull { it.accepted }
            ?: links.values.firstOrNull()
        if (c != null) {
            val p = peerInfo(c)
            return when {
                c.accepted -> State.Connected(p)
                c.outgoing -> State.WaitingAccept(p)
                else -> State.IncomingRequest(p)
            }
        }
        val target = dialing.firstOrNull() ?: reconnects.keys.firstOrNull()
        if (target != null) {
            val r = reconnects[target]
            return State.Connecting(peerInfoFor(target), if (r != null) r.attempt + 1 else 0, r != null)
        }
        return State.Idle
    }

    private fun publish() {
        val linked = links.values.filter { it.accepted }.map { it.address }.toSet()
        if (linked != connectedAddresses) {
            connectedAddresses = linked
            main.post { listeners.forEach { it.onLinksChanged(linked) } }
        }
        val s = computeState()
        if (s == state) return
        state = s
        main.post { listeners.forEach { it.onStateChanged(s) } }
    }

    private fun emitMessages(peer: String) {
        main.post { listeners.forEach { it.onMessagesChanged(peer) } }
    }

    private fun notice(@StringRes res: Int, vararg args: Any) {
        val text = ctx.getString(res, *args)
        main.post { listeners.forEach { it.onNotice(text) } }
    }

    private fun peerInfo(c: Conn): PeerInfo {
        val hello = c.hello ?: return peerInfoFor(c.address)
        return PeerInfo(c.address, hello.name, hello.role, c.deviceName)
    }

    private fun peerInfoFor(address: String): PeerInfo {
        val r = repo.peer(address)
        return PeerInfo(address, r?.name.orEmpty(), r?.role.orEmpty(), r?.deviceName.orEmpty())
    }

    private fun titleFor(address: String) = peerInfoFor(address).title

    private fun isBonded(address: String): Boolean = try {
        adapter?.bondedDevices?.any { it.address == address } == true
    } catch (e: SecurityException) {
        false
    }

    private fun deviceNameOf(d: BluetoothDevice): String = try {
        d.name.orEmpty()
    } catch (e: SecurityException) {
        ""
    }

    private fun closeQuietly(c: Closeable) {
        try {
            c.close()
        } catch (e: IOException) {
        }
    }

    /** Одно соединение. Читает в своём потоке, пишет через однопоточный исполнитель. */
    private inner class Conn(
        val socket: BluetoothSocket,
        val outgoing: Boolean,
        private val inp: DataInputStream,
        private val out: DataOutputStream
    ) {
        val seq = ++connSeq
        val address: String = socket.remoteDevice.address
        val deviceName: String = deviceNameOf(socket.remoteDevice)
        val createdAt = SystemClock.elapsedRealtime()
        var hello: Frame.Hello? = null
        var accepted = false

        /** Когда соединение стало «собеседником» (после HELLO) — от него считаются таймауты ответа. */
        var linkedAt = 0L

        /** Старые версии (до групп) не знают поле «g» и приняли бы сообщение группы за личное. */
        fun supportsGroups(): Boolean = (hello?.version ?: 0) >= GROUP_MIN_VERSION

        /** Старые версии не знают поле "origin" и приняли бы пересланный кадр за прямой от соседа. */
        fun supportsMesh(): Boolean = (hello?.version ?: 0) >= Protocol.MESH_MIN_VERSION

        /** Входящие голосовые передачи этого соединения: id → состояние (трогать только на потоке h). */
        val voiceIn = LinkedHashMap<String, VoiceIn>()

        /** Старые версии игнорируют голосовые кадры: им голос не шлём. */
        fun supportsVoice(): Boolean = (hello?.version ?: 0) >= Protocol.VOICE_MIN_VERSION

        @Volatile
        var lastRx = createdAt

        @Volatile
        var lastTx = createdAt

        @Volatile
        private var closing = false
        /**
         * Очередь отправки с приоритетами (см. [SendQueue]): тревога и служебные кадры обгоняют текст,
         * текст обгоняет голос. Пишет один поток, поэтому кадры в канале не перемешиваются.
         */
        private val queue = SendQueue()
        private val writerThread = Thread({ writeLoop() }, "bt-write").apply { isDaemon = true }
        private val writerStarted = AtomicBoolean(false)
        private val reader = Thread({ readLoop() }, "bt-read")

        fun start() {
            reader.start()
            ensureWriter()
        }

        /** Поток записи стартует при первой отправке: отказ «занято» уходит и у соединения, которое не стартовало. */
        private fun ensureWriter() {
            if (writerStarted.compareAndSet(false, true)) writerThread.start()
        }

        /** [ttlMs] > 0 — кусок рации: если за это время не ушёл в канал, выбрасывается. */
        fun send(frame: Frame, onWritten: (() -> Unit)? = null, ttlMs: Long = 0L) {
            if (closing) return
            queue.put(frame, onWritten, ttlMs)
            ensureWriter()
        }

        /** Отправить последний кадр (BYE/REJECT) и закрыть. Голос, не успевший уйти, выбрасывается. */
        fun closeGracefully(last: Frame?) {
            if (closing) return
            closing = true
            closeVoiceIn(this)
            queue.dropVoice()
            queue.putLast(last)
            ensureWriter()
        }

        fun closeNow() {
            closing = true
            closeVoiceIn(this)
            writerThread.interrupt()
            closeQuietly(socket)
        }

        private fun writeLoop() {
            try {
                while (true) {
                    val e = queue.take()
                    val frame = e.frame
                    if (e.closing) {
                        if (frame != null) {
                            Protocol.write(out, frame)
                            Thread.sleep(300) // дать кадру уйти, прежде чем рвать канал
                        }
                        break
                    }
                    if (frame == null) continue
                    Protocol.write(out, frame)
                    lastTx = SystemClock.elapsedRealtime()
                    val done = e.onWritten
                    if (done != null) h.post { done() }
                }
            } catch (e: IOException) {
                // поток чтения заметит обрыв и сообщит о нём
            } catch (e: InterruptedException) {
            }
            closeQuietly(socket)
        }

        private fun readLoop() {
            try {
                while (true) {
                    val f = Protocol.read(inp)
                    lastRx = SystemClock.elapsedRealtime()
                    h.post { onFrame(this, f) }
                }
            } catch (e: IOException) {
            }
            h.post { onConnClosed(this) }
        }
    }

    private companion object {
        /** Лимит одновременных соединений: у Bluetooth RFCOMM на телефоне их немного (обычно до 7 на всё). */
        const val MAX_LINKS = 4

        /** Голос. Рация: кусок старше этого к записи в канал выбрасывается (задержка рации не копится). */
        const val LIVE_TTL_MS = 500L
        const val LIVE_IDLE_MS = 8_000L
        const val VOICE_IDLE_MS = 60_000L
        const val MAX_VOICE_OUT = 8
        const val MAX_VOICE_IN = 4
        const val MAX_VOICE_NACKS = 2
        const val MAX_VOICE_DONE = 50
        const val GROUP_MIN_VERSION = 2
        const val TICK_MS = 5_000L
        const val PING_INTERVAL_MS = 10_000L
        const val DEAD_TIMEOUT_MS = 30_000L
        const val HELLO_TIMEOUT_MS = 15_000L
        const val REQUEST_TIMEOUT_MS = 60_000L
        const val RETRY_LISTEN_MS = 3_000L
        const val RECENT_DIAL_MS = 30_000L
        const val RECONNECT_WINDOW_MS = 5 * 60_000L
        val RECONNECT_DELAYS_MS = longArrayOf(1_500, 3_000, 5_000, 10_000, 20_000, 30_000)
    }
}
