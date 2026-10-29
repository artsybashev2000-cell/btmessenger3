package ru.btmessenger.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ru.btmessenger.app.BtLink.State
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), BtLink.Listener {

    companion object {
        const val EXTRA_PEER = "peer"
        private const val STATE_OPEN_CHAT = "open_chat"

        private const val M_CONNECT = 1
        private const val M_DISCONNECT = 2
        private const val M_SOS = 3
        private const val M_CLEAR = 4
        private const val M_VISIBLE = 5
        private const val M_REFRESH = 6
        private const val M_SHOW_ALL = 7
        private const val M_PROFILE = 8
        private const val M_EXIT = 9
        private const val M_ALARM = 10
        private const val M_NEW_GROUP = 11
        private const val M_GROUP_MEMBERS = 12
        private const val M_GROUP_CONNECT = 13
        private const val M_GROUP_LEAVE = 14
        private const val M_RELAY = 15
        private const val M_STILL = 16

        /** Варианты времени неподвижности и времени на ответ в окне настроек, секунды. */
        private val STILL_AFTER_OPTIONS = listOf(30, 60, 120, 180, 300, 600, 900, 1800)
        private val STILL_REPLY_OPTIONS = listOf(15, 30, 60, 90, 120, 180)

        /** Удержание дольше этого на микрофоне — рация, короче — нажатие (голосовое сообщение). */
        private const val MIC_HOLD_MS = 350L

        /** Запись короче этого не отправляем (случайное нажатие). */
        private const val MIN_VOICE_MS = 500

        /** Предел голосового сообщения с запасом до 60 секунд протокола: на пределе запись сама уходит. */
        private const val MSG_MAX_MS = 59_000

        /** Страховка: рация, у которой «залипла» кнопка, сама замолкает через 5 минут. */
        private const val RADIO_MAX_MS = 5 * 60_000L
    }

    private val link get() = core.link
    private val prefs get() = core.prefs
    private val repo get() = core.repo

    private var btAdapter: BluetoothAdapter? = null
    private lateinit var location: LocationHelper
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var btnBack: ImageButton
    private lateinit var topBar: View
    private lateinit var inputBar: View
    private lateinit var btnMore: ImageButton
    private lateinit var btnSos: TextView
    private lateinit var headerAvatar: TextView
    private lateinit var headerTitle: TextView
    private lateinit var headerSubtitle: TextView
    private lateinit var listPanel: View
    private lateinit var chatPanel: View
    private lateinit var banner: View
    private lateinit var bannerDot: View
    private lateinit var bannerText: TextView
    private lateinit var bannerAction: TextView
    private lateinit var fabScan: ImageButton
    private lateinit var homeList: RecyclerView
    private lateinit var messageList: RecyclerView
    private lateinit var offlineBar: View
    private lateinit var offlineText: TextView
    private lateinit var offlineAction: TextView
    private lateinit var geoProgress: TextView
    private lateinit var input: EditText
    private lateinit var searchInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnLocation: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var recordBar: View
    private lateinit var recordDot: View
    private lateinit var recordTimer: TextView
    private lateinit var recordHint: TextView
    private lateinit var recordCancel: ImageButton
    private lateinit var recordSend: ImageButton
    private lateinit var radioBar: TextView
    private lateinit var voicePlayer: VoicePlayer

    private lateinit var homeAdapter: HomeAdapter
    private lateinit var messageAdapter: MessageAdapter

    /** Адрес открытого чата (null — главный экран). */
    private var openChat: String? = null
    private var openChatRecord: PeerRecord? = null
    /** Открыть чат, как только установится связь с этим адресом (подключение начал пользователь). */
    private var openWhenConnected: String? = null
    private var lastState: State? = null
    private var resumed = false

    private var scanning = false
    private var foundThisScan = 0
    private var scanStoppedByUser = false
    private val nearby = LinkedHashMap<String, NearbyDevice>()

    /** Короткий id из BLE-объявления (BleAdvert.key) знакомого собеседника -> его адрес для RFCOMM. */
    private var bleKnown: Map<String, String> = emptyMap()

    /** Экран на виду (между onStart и onStop): только тогда слушаем BLE-объявления. */
    private var bleWanted = false
    private var summaries: List<PeerSummary> = emptyList()
    private var groupSummaries: List<GroupSummary> = emptyList()

    /** Id установки собеседников, с которыми сейчас есть соединение (для числа участников «в сети»). */
    private var connectedInstalls: Set<String> = emptySet()

    /** Открытая группа (если открыт групповой чат), сколько её участников на связи и имена авторов. */
    private var openGroup: GroupInfo? = null
    private var openGroupOnline = 0
    private var groupNames: Map<String, String> = emptyMap()

    private var geoRequest: LocationHelper.Request? = null
    private var geoPeer: String? = null
    private var requestDialog: AlertDialog? = null
    private var problemDialog: AlertDialog? = null
    private var requestFor: String? = null
    private var bannerHandler: (() -> Unit)? = null
    private var offlineHandler: (() -> Unit)? = null

    private var pendingBtAction: (() -> Unit)? = null
    private var pendingNeedScan = false
    private var afterLocationPermission: (() -> Unit)? = null
    private var receiverRegistered = false

    // ---------- Разрешения ----------

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            link.refresh()
            val needed = if (pendingNeedScan) scanPermissions() else corePermissions()
            if (needed.all { granted(it) }) {
                loadBonded()
                withBluetooth(pendingNeedScan, pendingBtAction)
            } else {
                pendingBtAction = null
                val forever = needed.any { !granted(it) && !shouldShowRequestPermissionRationale(it) }
                if (forever) {
                    showPermissionSettingsDialog(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.need_bt_permissions_settings
                        else R.string.need_location_for_scan_settings
                    )
                } else {
                    toast(R.string.need_bt_permissions)
                }
                renderState(link.state)
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) toast(R.string.notifications_denied_hint)
            renderState(link.state)
            if (pendingStillSetup) {
                pendingStillSetup = false
                continueStillSetup()
            }
        }

    /** Включили контроль неподвижности, ждём ответа на запрос уведомлений, чтобы продолжить настройку. */
    private var pendingStillSetup = false

    private val enableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (btAdapter?.isEnabled == true) {
                withBluetooth(pendingNeedScan, pendingBtAction)
            } else {
                pendingBtAction = null
                toast(R.string.bt_not_enabled)
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val action = afterLocationPermission
            afterLocationPermission = null
            if (result.values.any { it }) {
                action?.invoke()
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                showPermissionSettingsDialog(R.string.need_location_settings)
            } else {
                toast(R.string.need_location_permission)
            }
        }

    private var pendingMicAction: (() -> Unit)? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            val action = pendingMicAction
            pendingMicAction = null
            if (isGranted) {
                action?.invoke()
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                showPermissionSettingsDialog(R.string.need_mic_settings)
            } else {
                toast(R.string.need_mic_permission)
            }
        }

    /**
     * Как [withBluetooth], но для микрофона: есть разрешение — сразу действие, нет — запрос, затем действие.
     * Звать вокруг начала записи: withBluetooth { withMicrophone { recorder.start() } }.
     * Для рации «нажал и держи» после диалога запись лучше не стартовать сама (палец уже отпущен):
     * передавайте action = null и просто просите нажать ещё раз.
     */
    private fun withMicrophone(action: (() -> Unit)?) {
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            action?.invoke()
            return
        }
        pendingMicAction = action
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** На Android 12+ нужны разрешения «Устройства поблизости»; на 11 и ниже обычные Bluetooth-разрешения выдаются при установке. */
    private fun corePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            emptyList()
        }

    /** Для поиска устройств на Android 11 и ниже нужна ещё точная геопозиция. */
    private fun scanPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) corePermissions()
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /**
     * Единая точка входа для всего, что трогает Bluetooth: сначала разрешения, потом включение
     * адаптера, потом действие. Раньше кнопки работали и без разрешений — и приложение падало.
     */
    private fun withBluetooth(needScan: Boolean = false, action: (() -> Unit)?) {
        val a = btAdapter
        if (a == null) {
            toast(R.string.no_bluetooth)
            return
        }
        val missing = (if (needScan) scanPermissions() else corePermissions()).filter { !granted(it) }
        if (missing.isNotEmpty()) {
            pendingBtAction = action
            pendingNeedScan = needScan
            // уведомления не смешиваем с запросом Bluetooth: их просим отдельно, с объяснением (askNotificationsOnce)
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (!a.isEnabled) {
            pendingBtAction = action
            pendingNeedScan = needScan
            try {
                enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: ActivityNotFoundException) {
                toast(R.string.bt_not_enabled)
            } catch (e: SecurityException) {
                toast(R.string.need_bt_permissions)
            }
            return
        }
        pendingBtAction = null
        startLink()
        bleSync()
        action?.invoke()
    }

    /** Слушаем BLE-объявления, пока экран на виду и связь не выключена (Bluetooth включён, разрешения есть). */
    private fun bleSync() {
        if (bleWanted && link.state !is State.Off) core.ble.start() else core.ble.stop()
    }

    private fun startLink() {
        // Android 12+: служба типа connectedDevice без BLUETOOTH_CONNECT не запустится (на 14 — SecurityException)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            toast(R.string.need_bt_permissions)
            renderState(link.state)
            return
        }
        link.start() // сразу, чтобы следующее действие (connect) не обогнало запуск
        val i = Intent(this, LinkService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            // система может не дать запустить службу — связь работает, пока открыт экран
            toast(R.string.service_bg_not_started)
        }
        link.refresh()
        if (!askNotificationsOnce()) askAlarmDndOnce()
    }

    /** Android 13+: один раз объясняем, зачем нужны уведомления, и только потом показываем системный запрос. */
    private fun askNotificationsOnce(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (granted(Manifest.permission.POST_NOTIFICATIONS) || prefs.notificationsAsked) return false
        if (isFinishing || isDestroyed) return false
        prefs.notificationsAsked = true
        AlertDialog.Builder(this)
            .setTitle(R.string.notifications_title)
            .setMessage(R.string.notifications_why)
            .setPositiveButton(R.string.action_allow) { _, _ ->
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton(R.string.later) { _, _ -> renderState(link.state) }
            .show()
        return true
    }

    /** Один раз объясняем, как сделать, чтобы SOS звучал и в режиме «Не беспокоить». Решает сам пользователь. */
    private fun askAlarmDndOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || prefs.dndAsked) return
        if (isFinishing || isDestroyed) return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        prefs.dndAsked = true
        if (!core.notifier.sosBypassesDnd()) showAlarmDndDialog()
    }

    private fun showAlarmDndDialog() {
        val on = core.notifier.sosBypassesDnd()
        AlertDialog.Builder(this)
            .setTitle(R.string.dnd_title)
            .setMessage(if (on) R.string.dnd_on else R.string.dnd_why)
            .setPositiveButton(R.string.open_settings) { _, _ -> openSosChannelSettings() }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun openSosChannelSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val i = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.CH_SOS)
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            openNotificationSettings()
        }
    }

    private fun notificationsOff(): Boolean =
        prefs.notificationsAsked && !NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun openNotificationSettings() {
        val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        }
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.notifications_denied_hint)
        }
    }

    /** Фоновая служба не запустилась (причину записала LinkService): говорим, что случилось и что делать. */
    private fun showServiceProblem() {
        val res = core.serviceProblem
        if (res == 0 || !resumed || problemDialog?.isShowing == true) return
        core.serviceProblem = 0
        core.notifier.cancelProblem()
        problemDialog = AlertDialog.Builder(this)
            .setTitle(R.string.service_problem_title)
            .setMessage(res)
            .setPositiveButton(R.string.action_turn_on) { _, _ -> withBluetooth(action = null) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---------- Жизненный цикл ----------

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeChat() // «назад» больше не разрывает связь
    }

    /**
     * Интерфейс рисуется под системными панелями (на Android 15 при targetSdk 35 это обязательно).
     * Отступы берём из WindowInsets: сверху статус-бар/вырез, снизу навигация или клавиатура (что больше), по бокам — в ландшафте.
     */
    private fun setupInsets() {
        val root = findViewById<View>(R.id.mainRoot)
        val inputBase = inputBar.paddingBottom
        val listBase = homeList.paddingBottom
        val fabLp = fabScan.layoutParams as ViewGroup.MarginLayoutParams
        val fabBase = fabLp.bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val i = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(i.left, 0, i.right, 0)
            topBar.setPadding(0, i.top, 0, 0)
            inputBar.setPadding(inputBar.paddingLeft, inputBar.paddingTop, inputBar.paddingRight, inputBase + i.bottom)
            homeList.setPadding(homeList.paddingLeft, homeList.paddingTop, homeList.paddingRight, listBase + i.bottom)
            if (fabLp.bottomMargin != fabBase + i.bottom) {
                fabLp.bottomMargin = fabBase + i.bottom
                fabScan.layoutParams = fabLp
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // фон тёмный, значки системных панелей светлые, сами панели прозрачные
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupInsets()
        location = LocationHelper(applicationContext)
        btAdapter = getSystemService(BluetoothManager::class.java)?.adapter

        homeAdapter = HomeAdapter(::onPeerClick, ::onPeerLongClick, ::onDeviceClick, ::onGroupClick, ::onGroupLongClick)
        homeList.layoutManager = LinearLayoutManager(this)
        homeList.adapter = homeAdapter

        messageAdapter = MessageAdapter(::onGeoClick, ::copyMessage, ::onVoiceClick)
        voicePlayer = VoicePlayer(this)
        voicePlayer.onChange = { id, pos -> messageAdapter.setPlayback(id, pos) }
        voicePlayer.onError = { t ->
            toast(if (t is UnsupportedOperationException) R.string.voice_no_codec else R.string.voice_play_failed)
        }
        messageList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messageList.adapter = messageAdapter
        // когда открывается клавиатура, держим последнее сообщение на виду
        messageList.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom && messageAdapter.itemCount > 0) {
                messageList.post { messageList.scrollToPosition(messageAdapter.itemCount - 1) }
            }
        }

        findViewById<View>(R.id.rowVisible).setOnClickListener { makeDiscoverable() }
        fabScan.setOnClickListener { startScan() }
        btnBack.setOnClickListener { closeChat() }
        btnMore.setOnClickListener { showMenu() }
        btnSos.setOnClickListener { confirmSos() }
        btnSend.setOnClickListener { sendMessage() }
        btnLocation.setOnClickListener { sendLocation() }
        setupVoiceButtons()
        bannerAction.setOnClickListener { bannerHandler?.invoke() }
        banner.setOnClickListener { bannerHandler?.invoke() }
        offlineAction.setOnClickListener { offlineHandler?.invoke() }
        geoProgress.setOnClickListener { cancelGeo() }
        searchInput.doAfterTextChanged { rebuildHomeRows() }
        input.doAfterTextChanged { updateSendButton() }
        onBackPressedDispatcher.addCallback(this, backCallback)

        registerDiscoveryReceiver()

        val restore = savedInstanceState?.getString(STATE_OPEN_CHAT) ?: intent.getStringExtra(EXTRA_PEER)
        if (restore != null) openChatScreen(restore) else showChat(false)
        updateSendButton()

        if (btAdapter == null) return
        if (corePermissions().all { granted(it) }) loadBonded()
        if (!prefs.profileAsked) showProfileDialog(first = true) else withBluetooth(action = null)
    }

    private fun bindViews() {
        topBar = findViewById(R.id.topBar)
        inputBar = findViewById(R.id.inputBar)
        btnBack = findViewById(R.id.btnBack)
        btnMore = findViewById(R.id.btnMore)
        btnSos = findViewById(R.id.btnSos)
        headerAvatar = findViewById(R.id.headerAvatar)
        headerTitle = findViewById(R.id.headerTitle)
        headerSubtitle = findViewById(R.id.headerSubtitle)
        listPanel = findViewById(R.id.listPanel)
        chatPanel = findViewById(R.id.chatPanel)
        banner = findViewById(R.id.statusBanner)
        bannerDot = findViewById(R.id.bannerDot)
        bannerText = findViewById(R.id.bannerText)
        bannerAction = findViewById(R.id.bannerAction)
        fabScan = findViewById(R.id.fabScan)
        homeList = findViewById(R.id.homeList)
        messageList = findViewById(R.id.messageList)
        offlineBar = findViewById(R.id.offlineBar)
        offlineText = findViewById(R.id.offlineText)
        offlineAction = findViewById(R.id.offlineAction)
        geoProgress = findViewById(R.id.geoProgress)
        input = findViewById(R.id.input)
        searchInput = findViewById(R.id.searchInput)
        btnSend = findViewById(R.id.btnSend)
        btnLocation = findViewById(R.id.btnLocation)
        btnMic = findViewById(R.id.btnMic)
        recordBar = findViewById(R.id.recordBar)
        recordDot = findViewById(R.id.recordDot)
        recordTimer = findViewById(R.id.recordTimer)
        recordHint = findViewById(R.id.recordHint)
        recordCancel = findViewById(R.id.recordCancel)
        recordSend = findViewById(R.id.recordSend)
        radioBar = findViewById(R.id.radioBar)
    }

    override fun onStart() {
        super.onStart()
        link.addListener(this)
        bleWanted = true
        core.ble.onChanged = { if (!isDestroyed) rebuildHomeRows() }
        bleSync()
        hideRadioBar() // пока экрана не было, мы могли пропустить конец чужой рации
        lastState = link.state
        renderState(link.state)
        reloadHome()
        reloadChat()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        core.uiVisible = true
        link.visibleChat = openChat
        openChat?.let { markRead(it) }
        core.notifier.cancelRequest()
        syncRequestDialog(link.state)
        showServiceProblem()
    }

    override fun onPause() {
        cancelVoiceCapture(explain = true) // из фона микрофон всё равно не слышит: запись и рацию заканчиваем
        resumed = false
        core.uiVisible = false
        link.visibleChat = null
        super.onPause()
    }

    override fun onStop() {
        bleWanted = false
        core.ble.onChanged = null
        core.ble.stop()
        voicePlayer.stop()
        link.removeListener(this)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_OPEN_CHAT, openChat)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_PEER)?.let { openChatScreen(it) }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(discoveryReceiver)
            receiverRegistered = false
        }
        if (scanning) stopScan()
        geoRequest?.cancel()
        geoRequest = null
        requestDialog?.dismiss()
        problemDialog?.dismiss()
        cancelVoiceCapture()
        voiceHandler.removeCallbacksAndMessages(null)
        voicePlayer.release()
        io.shutdown()
        super.onDestroy()
    }

    private fun bg(block: () -> Unit) {
        try {
            io.execute(block)
        } catch (e: RejectedExecutionException) {
        }
    }

    // ---------- BtLink.Listener ----------

    override fun onStateChanged(state: State) {
        val prev = lastState
        lastState = state
        if (state is State.Connected) {
            val addr = state.peer.address
            val wasConnected = prev is State.Connected && prev.peer.address == addr
            if (!wasConnected) {
                if (openWhenConnected == addr) {
                    openWhenConnected = null
                    if (openChat != addr) openChatScreen(addr)
                } else if (openChat != addr) {
                    toast(getString(R.string.notice_connected, state.peer.title))
                }
            }
        } else if (state is State.Idle || state is State.Off) {
            openWhenConnected = null
        }
        bleSync() // Bluetooth выключили или включили: сканирование надо погасить или запустить заново
        renderState(state)
        reloadHome()
        if (openChat != null && state.peerAddress == openChat) reloadChat()
        showServiceProblem() // служба могла упасть, пока экран открыт
    }

    override fun onMessagesChanged(peer: String) {
        if (peer == openChat) {
            reloadChat()
            if (resumed) markRead(peer) else reloadHome()
        } else {
            reloadHome()
        }
    }

    override fun onIncoming(message: ChatMessage, peer: BtLink.PeerInfo) {
        if (message.kind == Kind.SOS) showSosDialog(message, peer)
    }

    override fun onMessageUpdated(message: ChatMessage, peer: BtLink.PeerInfo) {
        // Окно SOS уже открыто — показываем его заново со свежей точкой (кнопка «Направление» ведёт к ней)
        if (message.kind == Kind.SOS && message.id == sosDialogId && sosDialog?.isShowing == true) {
            sosDialog?.dismiss()
            showSosDialog(message, peer)
        }
    }

    override fun onNotice(text: String) = toast(text)

    override fun onVoiceStarted(peer: BtLink.PeerInfo, id: String, live: Boolean, chunkMs: Int) {
        if (!live || radioSession != null) return // сами говорим: чужую рацию не играем (см. LinkService)
        radioInId = id
        radioBar.text = getString(R.string.radio_incoming, peer.title)
        radioBar.visibility = View.VISIBLE
    }

    override fun onVoiceLiveEnded(peer: BtLink.PeerInfo, id: String, lostTail: Int) {
        if (id == radioInId) hideRadioBar()
    }

    override fun onVoiceFailed(peer: BtLink.PeerInfo, id: String, incoming: Boolean) {
        // для рации «не дошло» не бывает (она просто заканчивается), так что это голосовое сообщение
        if (incoming) toast(getString(R.string.voice_in_failed, peer.title))
    }

    override fun onLinksChanged(connected: Set<String>) {
        // кто на связи — от этого зависят значки «в сети» в списке и строка состояния группы
        reloadHome()
        if (groupIdFromKey(openChat) != null) reloadChat()
    }

    // ---------- Отрисовка состояния ----------

    private fun renderState(state: State) {
        if (openChat == null) renderHomeHeader(state) else renderChatHeader(state)
        renderBanner(state)
        renderOfflineBar(state)
        syncRequestDialog(state)
        fabScan.isEnabled = !scanning
        fabScan.alpha = if (scanning) 0.5f else 1f
    }

    private fun setSubtitle(text: String, accent: Boolean = false) {
        headerSubtitle.text = text
        headerSubtitle.setTextColor(
            ContextCompat.getColor(this, if (accent) R.color.tg_accent else R.color.tg_secondary)
        )
    }

    private fun renderHomeHeader(state: State) {
        headerTitle.text = getString(R.string.app_name)
        when (state) {
            is State.Off -> setSubtitle(getString(offText(state.reason)))
            is State.Idle -> setSubtitle(getString(if (scanning) R.string.status_scanning else R.string.status_idle))
            is State.Connecting -> setSubtitle(connectingText(state))
            is State.WaitingAccept -> setSubtitle(getString(R.string.status_waiting_accept, state.peer.title))
            is State.IncomingRequest -> setSubtitle(getString(R.string.status_request, state.peer.title), true)
            is State.Connected -> setSubtitle(getString(R.string.status_connected, state.peer.title), true)
        }
    }

    @StringRes
    private fun offText(r: BtLink.OffReason): Int = when (r) {
        BtLink.OffReason.NO_ADAPTER -> R.string.status_no_adapter
        BtLink.OffReason.BLUETOOTH_OFF -> R.string.status_bt_off
        BtLink.OffReason.NO_PERMISSION -> R.string.status_no_permission
        BtLink.OffReason.STOPPED -> R.string.status_stopped
    }

    private fun connectingText(s: State.Connecting): String =
        if (s.auto) getString(R.string.status_reconnecting, s.peer.title, s.attempt)
        else getString(R.string.status_connecting, s.peer.title)

    /** Сведения о собеседнике из текущего состояния, если оно про него. */
    private fun State.peerFor(address: String): BtLink.PeerInfo? = when (this) {
        is State.Connecting -> peer.takeIf { it.address == address }
        is State.WaitingAccept -> peer.takeIf { it.address == address }
        is State.IncomingRequest -> peer.takeIf { it.address == address }
        is State.Connected -> peer.takeIf { it.address == address }
        else -> null
    }

    private fun chatTitle(): String {
        val addr = openChat ?: return ""
        if (groupIdFromKey(addr) != null) {
            return openGroup?.name ?: groupSummaries.firstOrNull { it.info.chatKey == addr }?.info?.name.orEmpty()
        }
        val live = link.state.peerFor(addr)
        return live?.takeIf { it.name.isNotBlank() }?.title
            ?: openChatRecord?.title
            ?: summaries.firstOrNull { it.address == addr }?.title
            ?: live?.title
            ?: addr
    }

    /** Шапка группового чата: название, число участников и сколько из них на связи. */
    private fun renderGroupHeader(key: String) {
        headerTitle.text = chatTitle()
        headerAvatar.text = ""
        headerAvatar.background = groupAvatar(this, key, 40)
        val g = openGroup
        if (g == null) {
            setSubtitle("")
            return
        }
        val count = resources.getQuantityString(R.plurals.group_members_count, g.members.size, g.members.size)
        if (openGroupOnline > 0) setSubtitle(getString(R.string.group_subtitle_online, count, openGroupOnline), true)
        else setSubtitle(count)
    }

    private fun renderChatHeader(state: State) {
        val addr = openChat ?: return
        if (groupIdFromKey(addr) != null) {
            renderGroupHeader(addr)
            return
        }
        val title = chatTitle()
        headerTitle.text = title
        headerAvatar.text = avatarLetter(title)
        headerAvatar.background = circle(avatarColor(addr))
        val role = state.peerFor(addr)?.role?.takeIf { it.isNotBlank() } ?: openChatRecord?.role.orEmpty()
        when {
            state is State.Connected && state.peer.address == addr ->
                setSubtitle(if (role.isBlank()) getString(R.string.chat_online) else getString(R.string.chat_online_role, role), true)
            state is State.Connecting && state.peer.address == addr ->
                setSubtitle(
                    if (state.auto) getString(R.string.chat_reconnecting, state.attempt)
                    else getString(R.string.chat_connecting)
                )
            state is State.WaitingAccept && state.peer.address == addr -> setSubtitle(getString(R.string.chat_waiting))
            else -> setSubtitle(if (role.isBlank()) getString(R.string.chat_offline) else getString(R.string.chat_offline_role, role))
        }
    }

    private fun renderBanner(state: State) {
        fun show(text: String, action: Int?, dotColor: Int, handler: (() -> Unit)?) {
            banner.visibility = View.VISIBLE
            bannerText.text = text
            bannerAction.visibility = if (action != null) View.VISIBLE else View.GONE
            if (action != null) bannerAction.setText(action)
            bannerDot.background = circle(ContextCompat.getColor(this, dotColor))
            bannerHandler = handler
        }
        when (state) {
            is State.Off -> when (state.reason) {
                BtLink.OffReason.NO_ADAPTER -> show(getString(R.string.status_no_adapter), null, R.color.sos_red, null)
                BtLink.OffReason.BLUETOOTH_OFF ->
                    show(getString(R.string.banner_bt_off), R.string.action_turn_on, R.color.sos_red) { withBluetooth(action = null) }
                BtLink.OffReason.NO_PERMISSION ->
                    show(getString(R.string.banner_no_permission), R.string.action_allow, R.color.sos_red) { withBluetooth(action = null) }
                BtLink.OffReason.STOPPED ->
                    show(getString(R.string.banner_stopped), R.string.action_turn_on, R.color.tg_secondary) { withBluetooth(action = null) }
            }
            is State.Idle ->
                if (notificationsOff()) {
                    // без уведомлений SOS можно пропустить, пока приложение свёрнуто
                    show(getString(R.string.banner_notifications_off), R.string.action_allow, R.color.warning) {
                        openNotificationSettings()
                    }
                } else {
                    banner.visibility = View.GONE
                    bannerHandler = null
                }
            is State.Connecting -> show(connectingText(state), R.string.cancel, R.color.warning) { link.disconnect() }
            is State.WaitingAccept ->
                show(getString(R.string.status_waiting_accept, state.peer.title), R.string.cancel, R.color.warning) { link.disconnect() }
            is State.IncomingRequest ->
                show(getString(R.string.status_request, state.peer.title), R.string.action_answer, R.color.tg_accent) {
                    requestDialog?.dismiss()
                    requestDialog = null
                    syncRequestDialog(state)
                }
            is State.Connected ->
                show(getString(R.string.status_connected, state.peer.title), R.string.action_open, R.color.online) {
                    openChatScreen(state.peer.address)
                }
        }
        // в чате баннер не нужен — там своя строка состояния
        if (openChat != null) banner.visibility = View.GONE
    }

    private fun renderOfflineBar(state: State) {
        val addr = openChat
        if (addr == null) {
            offlineBar.visibility = View.GONE
            return
        }
        fun show(text: String, action: Int?, handler: (() -> Unit)?) {
            offlineBar.visibility = View.VISIBLE
            offlineText.text = text
            offlineAction.visibility = if (action != null) View.VISIBLE else View.GONE
            if (action != null) offlineAction.setText(action)
            offlineHandler = handler
        }
        val groupId = groupIdFromKey(addr)
        if (groupId != null) {
            // Групповой чат: «подключиться» означает «подключить тех участников, кто не на связи»
            val g = openGroup
            val others = g?.members?.count { it.installId != prefs.installId } ?: 0
            when {
                g == null -> offlineBar.visibility = View.GONE
                others == 0 -> show(getString(R.string.offline_group_alone), null, null)
                state is State.Off && state.reason == BtLink.OffReason.NO_ADAPTER ->
                    show(getString(R.string.status_no_adapter), null, null)
                openGroupOnline >= others -> {
                    offlineBar.visibility = View.GONE
                    offlineHandler = null
                }
                else -> show(getString(R.string.offline_group), R.string.action_connect) {
                    connectGroupMembers(groupId, auto = false)
                }
            }
            return
        }
        val connectHere: () -> Unit = { withBluetooth { link.connect(addr) } }
        when {
            state is State.Connected && state.peer.address == addr -> {
                offlineBar.visibility = View.GONE
                offlineHandler = null
            }
            (state is State.Connecting || state is State.WaitingAccept) && state.peerAddress == addr ->
                show(getString(R.string.offline_connecting), R.string.cancel) { link.disconnect() }
            state is State.Off && state.reason == BtLink.OffReason.NO_ADAPTER ->
                show(getString(R.string.status_no_adapter), null, null)
            state is State.Off -> show(getString(R.string.offline_bt_off), R.string.action_connect, connectHere)
            state.peerAddress != null && state.peerAddress != addr ->
                show(getString(R.string.offline_busy, state.peerFor(state.peerAddress ?: "")?.title ?: ""), R.string.action_switch, connectHere)
            else -> show(getString(R.string.offline_idle), R.string.action_connect, connectHere)
        }
    }

    private fun syncRequestDialog(state: State) {
        if (state is State.IncomingRequest) {
            if (!resumed) return
            if (requestDialog?.isShowing == true && requestFor == state.peer.address) return
            requestDialog?.dismiss()
            requestFor = state.peer.address
            requestDialog = AlertDialog.Builder(this)
                .setTitle(R.string.request_title)
                .setMessage(Format.peerDescription(this, state.peer))
                .setPositiveButton(R.string.accept) { _, _ ->
                    openWhenConnected = state.peer.address
                    link.acceptIncoming()
                }
                .setNegativeButton(R.string.reject) { _, _ -> link.rejectIncoming() }
                .setCancelable(false)
                .show()
        } else {
            requestDialog?.dismiss()
            requestDialog = null
            requestFor = null
        }
    }

    // ---------- Главный экран ----------

    private fun reloadHome() {
        bg {
            val list = repo.summaries()
            val groups = repo.groupSummaries()
            val installs = onlineInstallIds()
            val known = HashMap<String, String>()
            // знакомые идут от свежих к старым: если у человека несколько адресов, берём последний
            repo.knownPeers().forEach { known.putIfAbsent(BleAdvert.key(it.installId), it.address) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                bleKnown = known
                summaries = list
                groupSummaries = groups
                connectedInstalls = installs
                rebuildHomeRows()
                if (openChat != null) renderChatHeader(link.state)
            }
        }
    }

    private fun matches(q: String, vararg fields: String) =
        q.isEmpty() || fields.any { it.contains(q, ignoreCase = true) }

    private fun rebuildHomeRows() {
        val q = searchInput.text.toString().trim()
        val online = link.connectedAddresses
        val known = summaries.mapTo(HashSet()) { it.address }
        val rows = ArrayList<HomeRow>()

        // BLE: знакомых узнаём по короткому id (или по совпавшему адресу) и берём у них сигнал,
        // незнакомых показываем среди устройств; те, что уже есть в классическом списке, склеиваются по адресу
        val bleSignal = HashMap<String, Int>()
        val bleOnly = ArrayList<BleSightings.Sighting>()
        for (sg in core.ble.nearby()) {
            val addr = bleKnown[sg.key] ?: sg.bleAddress.takeIf { it in known }
            if (addr != null) bleSignal[addr] = sg.rssi else bleOnly += sg
        }
        val pool = LinkedHashMap<String, NearbyDevice>(nearby)
        for (sg in bleOnly) {
            val old = pool[sg.bleAddress]
            pool[sg.bleAddress] = old?.copy(rssi = sg.rssi, ble = true)
                ?: NearbyDevice(sg.bleAddress, "", false, null, sg.rssi, ble = true)
        }

        // Личные чаты и группы — одним списком, сверху те, где было последнее сообщение
        val peers = summaries.filter { matches(q, it.title, it.address, it.role) }
        val groups = groupSummaries.filter { matches(q, it.info.name) }
        if (peers.isNotEmpty() || groups.isNotEmpty()) {
            rows += HomeRow.Header(getString(R.string.section_chats))
            val chats = ArrayList<Triple<Long, Long, HomeRow>>()
            peers.forEach {
                chats += Triple(it.lastSeq, it.lastTime, HomeRow.Peer(it, it.address in online, bleSignal[it.address] ?: nearby[it.address]?.rssi))
            }
            groups.forEach { chats += Triple(it.lastSeq, it.lastTime, HomeRow.Group(it, groupOnline(it.info))) }
            chats.sortWith(compareByDescending<Triple<Long, Long, HomeRow>> { it.first }.thenByDescending { it.second })
            chats.forEach { rows += it.third }
        }

        val showAll = prefs.showAllDevices
        val devices = pool.values
            .filter { it.address !in known && (showAll || it.looksLikePhone()) && matches(q, it.title, it.address) }
            .sortedWith(
                compareByDescending<NearbyDevice> { it.rssi != null }
                    .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                    .thenByDescending { it.bonded }
            )
        rows += HomeRow.Header(getString(if (scanning) R.string.section_devices_scanning else R.string.section_devices))
        if (devices.isEmpty()) {
            rows += HomeRow.Hint(
                getString(
                    when {
                        btAdapter == null -> R.string.status_no_adapter
                        scanning -> R.string.devices_scanning_hint
                        q.isNotEmpty() -> R.string.devices_not_matched
                        else -> R.string.devices_empty_hint
                    }
                )
            )
        } else {
            devices.forEach { rows += HomeRow.Device(it) }
        }
        homeAdapter.submitList(rows)
    }

    private fun onPeerClick(s: PeerSummary) {
        openChatScreen(s.address)
        // если ни с кем не на связи — сразу подключаемся
        if (link.state is State.Idle) link.connect(s.address)
    }

    private fun onPeerLongClick(s: PeerSummary) {
        val items = arrayOf<CharSequence>(getString(R.string.menu_connect), getString(R.string.menu_forget))
        AlertDialog.Builder(this)
            .setTitle(s.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> withBluetooth {
                        openWhenConnected = s.address
                        link.connect(s.address)
                    }
                    1 -> confirmForget(s)
                }
            }
            .show()
    }

    private fun confirmForget(s: PeerSummary) {
        AlertDialog.Builder(this)
            .setTitle(R.string.forget_title)
            .setMessage(getString(R.string.forget_text, s.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (openChat == s.address) closeChat()
                link.forgetPeer(s.address)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Группы ----------

    /** Id установки тех, с кем есть принятое соединение (читает базу — только не на главном потоке). */
    private fun onlineInstallIds(): Set<String> =
        link.connectedAddresses.mapNotNullTo(HashSet()) { a -> repo.peer(a)?.installId?.takeIf { it.isNotBlank() } }

    private fun groupOnline(g: GroupInfo): Int =
        g.members.count { it.installId != prefs.installId && it.installId in connectedInstalls }

    /** Имена участников для подписей над сообщениями: свежее имя из профиля собеседника, иначе то, что пришло с составом. */
    private fun memberNames(g: GroupInfo): Map<String, String> = g.members.associate { m ->
        m.installId to (repo.peerByInstallId(m.installId)?.name?.takeIf { it.isNotBlank() } ?: m.name)
    }

    private fun authorName(senderId: String?): String =
        senderId?.let { groupNames[it] }?.takeIf { it.isNotBlank() } ?: getString(R.string.group_member_unknown)

    private fun onGroupClick(s: GroupSummary) {
        openChatScreen(s.info.chatKey)
        connectGroupMembers(s.info.id, auto = true)
    }

    private fun onGroupLongClick(s: GroupSummary) {
        val items = arrayOf<CharSequence>(
            getString(R.string.menu_group_members),
            getString(R.string.menu_group_connect),
            getString(R.string.menu_leave_group)
        )
        AlertDialog.Builder(this)
            .setTitle(s.info.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showGroupMembers(s.info.id)
                    1 -> connectGroupMembers(s.info.id, auto = false)
                    2 -> confirmLeaveGroup(s.info.id, s.info.name)
                }
            }
            .show()
    }

    /**
     * Подключиться к участникам группы, которые не на связи (адрес известен, если с ними уже соединялись).
     * [auto] — при открытии чата: молча и только если Bluetooth включён; по кнопке — с проверкой разрешений и пояснениями.
     */
    private fun connectGroupMembers(groupId: String, auto: Boolean) {
        bg {
            val g = repo.group(groupId) ?: return@bg
            val connected = link.connectedAddresses
            val absent = g.members.filter { it.installId != prefs.installId }.map { m ->
                repo.peerByInstallId(m.installId)?.address
            }.filter { it == null || it !in connected }
            val targets = absent.filterNotNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (targets.isEmpty()) {
                    if (!auto) toast(if (absent.isEmpty()) R.string.group_all_connected else R.string.group_nobody_to_connect)
                    return@runOnUiThread
                }
                val free = (link.maxLinks - connected.size).coerceAtLeast(0)
                val dial = {
                    if (free == 0) toast(getString(R.string.notice_too_many_links, link.maxLinks))
                    else targets.take(free).forEach { link.connect(it, keepOthers = true) }
                }
                if (auto) {
                    if (link.state !is State.Off) dial()
                } else {
                    withBluetooth { dial() }
                }
            }
        }
    }

    /** Окно «Новая группа»: название и выбор из знакомых собеседников (тех, чей id установки уже известен). */
    private fun showNewGroupDialog() {
        bg {
            val peers = repo.knownPeers()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (peers.isEmpty()) {
                    toast(R.string.group_no_contacts)
                    return@runOnUiThread
                }
                val v = layoutInflater.inflate(R.layout.dialog_new_group, null)
                val nameField = v.findViewById<EditText>(R.id.groupName)
                val list = v.findViewById<ListView>(R.id.pickList)
                list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
                list.adapter = ArrayAdapter(this, R.layout.item_pick_peer, peers.map { it.title })
                if (peers.size > 5) {
                    list.layoutParams = list.layoutParams.apply { height = (resources.displayMetrics.density * 240).toInt() }
                }
                val dlg = AlertDialog.Builder(this)
                    .setTitle(R.string.group_new_title)
                    .setView(v)
                    .setPositiveButton(R.string.group_create) { _, _ ->
                        val title = nameField.text.toString().trim()
                        val addresses = peers.filterIndexed { i, _ -> list.isItemChecked(i) }.map { it.address }
                        if (title.isNotEmpty() && addresses.isNotEmpty()) startGroupCreation(title, addresses)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .create()
                // «Создать» доступна, когда есть название и хотя бы один участник
                val update = {
                    dlg.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled =
                        nameField.text.isNotBlank() && list.checkedItemCount > 0
                }
                nameField.doAfterTextChanged { update() }
                list.setOnItemClickListener { _, _, position, _ ->
                    if (list.checkedItemCount > link.maxLinks) {
                        // больше соединений телефон не держит — пригласить всех сразу не получится
                        list.setItemChecked(position, false)
                        toast(getString(R.string.group_pick_limit, link.maxLinks))
                    }
                    update()
                }
                dlg.setOnShowListener { update() }
                dlg.show()
            }
        }
    }

    private fun startGroupCreation(title: String, addresses: List<String>) = withBluetooth {
        link.createGroup(title, addresses) { id ->
            if (isDestroyed) return@createGroup
            if (id == null) {
                toast(R.string.group_create_failed)
            } else {
                toast(R.string.group_created)
                openChatScreen(groupChatKey(id))
            }
        }
    }

    /** Состав группы: кто в ней и кто сейчас на связи. */
    private fun showGroupMembers(groupId: String) {
        bg {
            val g = repo.group(groupId)
            val names = if (g != null) memberNames(g) else emptyMap()
            val online = onlineInstallIds()
            runOnUiThread {
                if (isDestroyed || g == null) return@runOnUiThread
                val lines = g.members.map { m ->
                    val name = names[m.installId].orEmpty().ifBlank { getString(R.string.group_member_unknown) }
                    when {
                        m.installId == prefs.installId ->
                            if (prefs.userName.isBlank()) getString(R.string.group_you)
                            else getString(R.string.group_member_me, prefs.userName)
                        m.installId in online -> getString(R.string.group_member_online, name)
                        else -> getString(R.string.group_member_offline, name)
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.group_members_title, g.name))
                    .setMessage(lines.joinToString("\n"))
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }
    }

    private fun confirmLeaveGroup(groupId: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.leave_group_title)
            .setMessage(getString(R.string.leave_group_text, name))
            .setPositiveButton(R.string.leave_group_action) { _, _ ->
                val key = groupChatKey(groupId)
                if (openChat == key) closeChat()
                core.notifier.cancelChat(key)
                link.leaveGroup(groupId)
                toast(getString(R.string.group_left_toast, name))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onDeviceClick(d: NearbyDevice) = withBluetooth {
        stopScan()
        openWhenConnected = d.address
        link.connect(d.address)
    }

    // ---------- Поиск устройств ----------

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = intent.device() ?: return
                    val rssiRaw = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val rssi = if (rssiRaw == Short.MIN_VALUE) null else rssiRaw.toInt()
                    if (!nearby.containsKey(d.address) || nearby[d.address]?.rssi == null) foundThisScan++
                    putDevice(d, intent.getStringExtra(BluetoothDevice.EXTRA_NAME), rssi, intent.btClass()?.majorDeviceClass)
                    rebuildHomeRows()
                }
                BluetoothDevice.ACTION_NAME_CHANGED -> {
                    val d = intent.device() ?: return
                    val old = nearby[d.address] ?: return
                    val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    if (!name.isNullOrBlank()) nearby[d.address] = old.copy(name = name)
                    rebuildHomeRows()
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    scanning = true
                    renderState(link.state)
                    rebuildHomeRows()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    val wasScanning = scanning
                    scanning = false
                    renderState(link.state)
                    rebuildHomeRows()
                    if (wasScanning && !scanStoppedByUser && foundThisScan == 0) toast(R.string.scan_nothing_found)
                    scanStoppedByUser = false
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.device(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @Suppress("DEPRECATION")
    private fun Intent.btClass(): BluetoothClass? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_CLASS)
        }

    private fun registerDiscoveryReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(this, discoveryReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun putDevice(d: BluetoothDevice, nameHint: String?, rssi: Int?, majorClass: Int?) {
        val old = nearby[d.address]
        val name = try {
            d.name
        } catch (e: SecurityException) {
            null
        }
        val bonded = try {
            d.bondState == BluetoothDevice.BOND_BONDED
        } catch (e: SecurityException) {
            old?.bonded ?: false
        }
        val cls = majorClass ?: try {
            d.bluetoothClass?.majorDeviceClass
        } catch (e: SecurityException) {
            null
        }
        nearby[d.address] = NearbyDevice(
            address = d.address,
            name = name?.takeIf { it.isNotBlank() } ?: nameHint?.takeIf { it.isNotBlank() } ?: old?.name.orEmpty(),
            bonded = bonded,
            majorClass = cls ?: old?.majorClass,
            rssi = rssi ?: old?.rssi
        )
    }

    private fun loadBonded() {
        val a = btAdapter ?: return
        val bonded = try {
            a.bondedDevices
        } catch (e: SecurityException) {
            null
        } ?: return
        bonded.forEach { putDevice(it, null, null, null) }
        rebuildHomeRows()
    }

    private fun startScan() = withBluetooth(needScan = true) {
        // На Android 11 и ниже поиск без включённой геолокации молча ничего не находит
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !location.isEnabled()) {
            showLocationSettingsDialog(R.string.location_off_for_scan)
            return@withBluetooth
        }
        val a = btAdapter ?: return@withBluetooth
        try {
            if (a.isDiscovering) {
                scanStoppedByUser = true
                a.cancelDiscovery()
            }
            if (a.startDiscovery()) {
                scanning = true
                foundThisScan = 0
                scanStoppedByUser = false
                nearby.values.removeAll { !it.bonded }
                renderState(link.state)
                rebuildHomeRows()
            } else {
                toast(R.string.scan_failed)
            }
        } catch (e: SecurityException) {
            toast(R.string.need_bt_permissions)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanStoppedByUser = true
        try {
            btAdapter?.cancelDiscovery()
        } catch (e: SecurityException) {
        }
    }

    private fun makeDiscoverable() = withBluetooth {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.visible_failed)
        } catch (e: SecurityException) {
            toast(R.string.need_bt_permissions)
        }
    }

    // ---------- Чат ----------

    private fun openChatScreen(address: String) {
        if (openChat != address) {
            cancelVoiceCapture()
            voicePlayer.stop()
            messageAdapter.submitList(emptyList())
            messageAdapter.authors = emptyMap()
            input.setText("")
            openGroup = null
            openGroupOnline = 0
            groupNames = emptyMap()
        }
        openChat = address
        openChatRecord = null
        if (resumed) {
            link.visibleChat = address
            markRead(address)
        }
        core.notifier.cancelChat(address)
        showChat(true)
        reloadChat(scrollToEnd = true)
        if (groupIdFromKey(address) == null && !prefs.voiceHintShown) {
            prefs.voiceHintShown = true
            toast(R.string.voice_hint_once)
        }
    }

    private fun closeChat() {
        cancelVoiceCapture()
        voicePlayer.stop()
        openChat = null
        openChatRecord = null
        openGroup = null
        openGroupOnline = 0
        link.visibleChat = null
        showChat(false)
        reloadHome()
    }

    private fun showChat(show: Boolean) {
        backCallback.isEnabled = show
        listPanel.visibility = if (show) View.GONE else View.VISIBLE
        chatPanel.visibility = if (show) View.VISIBLE else View.GONE
        btnBack.visibility = if (show) View.VISIBLE else View.GONE
        headerAvatar.visibility = if (show) View.VISIBLE else View.GONE
        btnSos.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) hideKeyboard()
        updateGeoProgress()
        updateSendButton()
        renderState(link.state)
    }

    private fun markRead(peer: String) {
        bg {
            val groupId = groupIdFromKey(peer)
            if (groupId != null) repo.clearGroupUnread(groupId) else repo.clearUnread(peer)
            runOnUiThread { if (!isDestroyed) reloadHome() }
        }
    }

    private fun reloadChat(scrollToEnd: Boolean = false) {
        val peer = openChat ?: return
        bg {
            val list = repo.messages(peer)
            val groupId = groupIdFromKey(peer)
            val record = if (groupId == null) repo.peer(peer) else null
            val group = groupId?.let { repo.group(it) }
            val names = if (group != null) memberNames(group) else emptyMap()
            val installs = if (group != null) onlineInstallIds() else emptySet()
            runOnUiThread {
                if (isDestroyed || openChat != peer) return@runOnUiThread
                if (groupId != null && group == null) {
                    closeChat() // группы больше нет (вышли из неё) — возвращаемся к списку
                    return@runOnUiThread
                }
                openChatRecord = record
                openGroup = group
                openGroupOnline = group?.members?.count { it.installId != prefs.installId && it.installId in installs } ?: 0
                groupNames = names
                messageAdapter.authors = names
                renderChatHeader(link.state)
                renderOfflineBar(link.state)
                val lm = messageList.layoutManager as LinearLayoutManager
                val atEnd = scrollToEnd || lm.findLastVisibleItemPosition() >= messageAdapter.itemCount - 2
                messageAdapter.submitList(list) {
                    if (atEnd && list.isNotEmpty()) messageList.scrollToPosition(list.size - 1)
                }
            }
        }
    }

    /**
     * Сообщение сначала сохраняется (значок часов), потом уходит. Раньше при обрыве связи
     * оно пропадало вместе с очищенным полем ввода.
     */
    private fun sendMessage() {
        val peer = openChat ?: return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val groupId = groupIdFromKey(peer)
        if (groupId != null) {
            // Группа: уходит тем, кто на связи, остальным — когда подключатся (кнопка в полосе над вводом)
            link.sendGroupText(groupId, text)
            input.setText("")
            return
        }
        link.sendText(peer, text)
        input.setText("")
        if (link.state is State.Idle) link.connect(peer)
    }

    private fun updateSendButton() {
        val hasText = input.text.isNotBlank()
        // голос только в личных чатах: в группах он не поддерживается
        val personal = openChat != null && groupIdFromKey(openChat) == null
        btnSend.visibility = if (hasText) View.VISIBLE else View.GONE
        btnLocation.visibility = if (hasText) View.GONE else View.VISIBLE
        btnMic.visibility = if (!hasText && personal) View.VISIBLE else View.GONE
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun copyMessage(m: ChatMessage) {
        if (m.kind == Kind.VOICE) return // копировать у голосового нечего
        val text = when (m.kind) {
            Kind.TEXT -> m.text
            else -> listOfNotNull(m.text.takeIf { it.isNotBlank() }, m.geo?.let { Format.coords(it) }).joinToString("\n")
        }
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        toast(R.string.copied)
    }

    // ---------- Голос и рация ----------
    //
    // Одна кнопка микрофона, два жеста, чтобы не путалось:
    //   удержание (дольше MIC_HOLD_MS) — РАЦИЯ: звук уходит, пока палец на кнопке; отпустили — конец;
    //   короткое нажатие — ГОЛОСОВОЕ СООБЩЕНИЕ: идёт запись, на панели таймер и кнопки ✕ (отмена) и ➤ (отправить).
    // Панель записи у режимов разная: рация — зелёная, без кнопок, с подписью «вы в эфире»; сообщение — обычная.

    private val voiceHandler = Handler(Looper.getMainLooper())

    /** Идёт рация (мы говорим). [live] появляется сразу после создания, [rec] — после. */
    private class RadioTx(val startedAt: Long) {
        @Volatile var live: BtLink.LiveVoice? = null
        var rec: VoiceRecorder? = null
    }

    /** Идёт запись голосового сообщения: куски копятся в [chunks] (из потока записи, поэтому под замком). */
    private class MessageRec(val peer: String) {
        val chunks = ArrayList<ByteArray>()
        var chunkMs = 0
        var rec: VoiceRecorder? = null
    }

    private var radioSession: RadioTx? = null
    private var messageRec: MessageRec? = null

    /** Id входящей рации, которую показывает зелёная плашка наверху. */
    private var radioInId: String? = null

    private var micHoldFired = false
    private var micDownNoPermission = false
    private val micHoldRunnable = Runnable {
        micHoldFired = true
        startRadio()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupVoiceButtons() {
        btnMic.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    micHoldFired = false
                    micDownNoPermission = !granted(Manifest.permission.RECORD_AUDIO)
                    // удержание ждём, только если можно начать рацию прямо сейчас (разрешение есть, запись не идёт)
                    if (!micDownNoPermission && messageRec == null && radioSession == null) {
                        voiceHandler.postDelayed(micHoldRunnable, MIC_HOLD_MS)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    voiceHandler.removeCallbacks(micHoldRunnable)
                    val up = event.actionMasked == MotionEvent.ACTION_UP
                    when {
                        micHoldFired -> stopRadio() // отпустили после удержания: рация закончилась
                        up -> onMicTap()
                    }
                }
            }
            true
        }
        // «Нажать» без касания (TalkBack, клавиатура): то же, что короткое нажатие
        btnMic.setOnClickListener { onMicTap() }
        recordCancel.setOnClickListener { finishMessage(send = false) }
        recordSend.setOnClickListener { finishMessage(send = true) }
    }

    /** Короткое нажатие: начать запись сообщения, а если запись уже идёт — отправить её. */
    private fun onMicTap() {
        if (messageRec != null) {
            finishMessage(send = true)
            return
        }
        if (radioSession != null) return
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            // Запрос разрешения: после диалога запись сама не стартует (палец давно отпущен), просим нажать ещё раз
            withMicrophone { toast(R.string.mic_granted_retry) }
            return
        }
        startMessageRecording()
    }

    private fun isLinkedTo(peer: String): Boolean {
        val st = link.state
        return st is State.Connected && st.peer.address == peer
    }

    private fun startMessageRecording() {
        val peer = openChat ?: return
        if (groupIdFromKey(peer) != null) return
        hideKeyboard()
        voicePlayer.stop()
        val m = MessageRec(peer)
        val rec = VoiceRecorder(
            this,
            onChunk = { c -> synchronized(m.chunks) { m.chunks.add(c) } },
            onError = { t -> runOnUiThread { onRecorderError(t, m, null) } }
        )
        m.rec = rec
        m.chunkMs = rec.chunkMs
        if (!rec.start()) return // причина уже показана через onError
        messageRec = m
        showRecordBar(radio = false)
        voiceHandler.post(recordTicker)
    }

    /** Закончить запись сообщения: [send] — отправить, иначе выбросить. */
    private fun finishMessage(send: Boolean) {
        val m = messageRec ?: return
        messageRec = null
        voiceHandler.removeCallbacks(recordTicker)
        hideRecordBar()
        m.rec?.stop() // дошлёт последний кусок
        if (!send) return
        val chunks = synchronized(m.chunks) { m.chunks.toList() }
        if (chunks.size * m.chunkMs < MIN_VOICE_MS) {
            toast(R.string.rec_too_short)
            return
        }
        // Сообщение сохраняется в чат и уходит (сразу или при подключении), как и текст
        if (link.sendVoiceMessage(m.peer, chunks, m.chunkMs) == null) {
            toast(R.string.voice_send_failed)
            return
        }
        if (link.state is State.Idle) link.connect(m.peer)
    }

    private fun startRadio() {
        val peer = openChat ?: return
        if (groupIdFromKey(peer) != null || radioSession != null || messageRec != null) return
        if (!isLinkedTo(peer)) {
            toast(R.string.radio_need_link)
            return
        }
        hideKeyboard()
        voicePlayer.stop()
        core.live.stopAll() // входящую рацию, если играла, обрываем: говорим мы
        hideRadioBar()
        val tx = RadioTx(SystemClock.elapsedRealtime())
        val rec = VoiceRecorder(
            this,
            onChunk = { c -> tx.live?.push(c) },
            onError = { t -> runOnUiThread { onRecorderError(t, null, tx) } }
        )
        tx.rec = rec
        core.radioTx = true
        val live = link.startLive(peer, rec.chunkMs)
        tx.live = live
        if (!rec.start()) {
            live.end()
            core.radioTx = false
            return
        }
        radioSession = tx
        btnMic.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showRecordBar(radio = true)
        voiceHandler.post(recordTicker)
    }

    private fun stopRadio() {
        val tx = radioSession ?: return
        radioSession = null
        voiceHandler.removeCallbacks(recordTicker)
        hideRecordBar()
        tx.rec?.stop() // последний кусок уходит через push
        tx.live?.end()
        core.radioTx = false
    }

    /** Закончить всё, что пишется (смена чата, уход с экрана). Сообщение при этом выбрасывается, а не отправляется. */
    private fun cancelVoiceCapture(explain: Boolean = false) {
        if (radioSession != null) stopRadio()
        if (messageRec != null) {
            finishMessage(send = false)
            if (explain) toast(R.string.rec_cancelled_bg)
        }
    }

    private fun onRecorderError(t: Throwable, m: MessageRec?, r: RadioTx?) {
        toast(
            when (t) {
                is SecurityException -> R.string.need_mic_permission
                is UnsupportedOperationException -> R.string.voice_no_codec
                else -> R.string.voice_mic_busy
            }
        )
        // ошибка посреди записи: рекордер уже остановился сам, закрываем то, что с ним связано
        if (m != null && messageRec === m) finishMessage(send = false)
        if (r != null && radioSession === r) stopRadio()
    }

    private fun showRecordBar(radio: Boolean) {
        recordBar.setBackgroundColor(ContextCompat.getColor(this, if (radio) R.color.radio_live else R.color.tg_header))
        recordHint.setText(if (radio) R.string.rec_hint_radio else R.string.rec_hint_message)
        recordHint.setTextColor(ContextCompat.getColor(this, if (radio) R.color.text_primary else R.color.tg_secondary_light))
        val buttons = if (radio) View.GONE else View.VISIBLE
        recordCancel.visibility = buttons
        recordSend.visibility = buttons
        recordTimer.text = Format.mmss(0L)
        recordDot.alpha = 1f
        recordBar.visibility = View.VISIBLE
        ImageViewCompat.setImageTintList(btnMic, ColorStateList.valueOf(ContextCompat.getColor(this, R.color.sos_red)))
    }

    private fun hideRecordBar() {
        recordBar.visibility = View.GONE
        ImageViewCompat.setImageTintList(btnMic, ColorStateList.valueOf(ContextCompat.getColor(this, R.color.tg_secondary)))
    }

    /** Таймер на панели записи; на пределе длины сообщение уходит само. */
    private val recordTicker = object : Runnable {
        override fun run() {
            val m = messageRec
            val tx = radioSession
            if (m != null) {
                val ms = synchronized(m.chunks) { m.chunks.size } * m.chunkMs
                recordTimer.text = Format.mmss(ms.toLong())
                if (ms >= MSG_MAX_MS) {
                    toast(R.string.rec_limit_reached)
                    finishMessage(send = true)
                    return
                }
            } else if (tx != null) {
                val ms = SystemClock.elapsedRealtime() - tx.startedAt
                recordTimer.text = Format.mmss(ms)
                if (ms >= RADIO_MAX_MS) {
                    stopRadio()
                    return
                }
            } else {
                return
            }
            recordDot.alpha = if (recordDot.alpha < 1f) 1f else 0.3f // мигает: идёт запись
            voiceHandler.postDelayed(this, 500L)
        }
    }

    private fun hideRadioBar() {
        radioInId = null
        radioBar.visibility = View.GONE
    }

    /** Нажатие на кнопку проигрывания в ленте: запустить сообщение или остановить, если оно уже играет. */
    private fun onVoiceClick(m: ChatMessage) {
        if (voicePlayer.playingId == m.id) {
            voicePlayer.stop()
            return
        }
        core.live.stopAll() // поверх рации не играем
        val appCtx = applicationContext
        bg {
            val frames = VoiceStore.frames(appCtx, m.id) // чтение файла — не на главном потоке
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (frames == null) toast(R.string.voice_file_missing) else voicePlayer.play(m.id, frames)
            }
        }
    }

    // ---------- Геопозиция ----------

    private fun requestLocationPermission(then: () -> Unit) {
        afterLocationPermission = then
        locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    private fun sendLocation() {
        val peer = openChat ?: return
        if (geoRequest != null) return // уже определяем — повторные нажатия не плодят сообщения
        if (!location.hasPermission()) {
            requestLocationPermission { sendLocation() }
            return
        }
        if (!location.isEnabled()) {
            showLocationSettingsDialog(R.string.location_off_for_geo)
            return
        }
        if (!location.hasActiveProvider()) {
            toast(R.string.geo_no_source)
            return
        }
        geoPeer = peer
        val req = location.requestFix { geo ->
            geoRequest = null
            geoPeer = null
            updateGeoProgress()
            if (geo == null) {
                toast(R.string.geo_failed)
            } else {
                val groupId = groupIdFromKey(peer)
                if (groupId != null) {
                    link.sendGroupGeo(groupId, geo)
                } else {
                    link.sendGeo(peer, geo)
                    if (link.state is State.Idle) link.connect(peer)
                }
            }
        }
        geoRequest = req
        if (req == null) geoPeer = null
        updateGeoProgress()
    }

    private fun cancelGeo() {
        geoRequest?.cancel()
        geoRequest = null
        geoPeer = null
        updateGeoProgress()
    }

    private fun updateGeoProgress() {
        val busy = geoRequest != null
        geoProgress.visibility = if (busy && openChat != null && openChat == geoPeer) View.VISIBLE else View.GONE
        btnLocation.isEnabled = !busy
        btnLocation.alpha = if (busy) 0.4f else 1f
    }

    private fun onGeoClick(m: ChatMessage) {
        val g = m.geo ?: return
        val label = when {
            m.fromMe -> getString(R.string.my_location)
            m.groupId != null -> authorName(m.senderId) // в группе точка — от конкретного участника
            else -> chatTitle()
        }
        val items = arrayOf<CharSequence>(
            getString(R.string.geo_direction),
            getString(R.string.geo_open_maps),
            getString(R.string.geo_open_yandex),
            getString(R.string.geo_copy)
        )
        AlertDialog.Builder(this)
            .setTitle(Format.coords(g))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDirection(g, label, m.sentAt)
                    1 -> MapLinks.openGeo(this, g, label)
                    2 -> MapLinks.openYandex(this, g)
                    3 -> MapLinks.copy(this, g)
                }
            }
            .show()
    }

    private fun showDirection(g: GeoPoint, label: String, sentAt: Long) {
        if (!location.hasPermission()) {
            requestLocationPermission { showDirection(g, label, sentAt) }
            return
        }
        if (!location.isEnabled()) {
            showLocationSettingsDialog(R.string.location_off_for_direction)
            return
        }
        DirectionDialog(this, g, label, sentAt).show()
    }

    // ---------- SOS ----------

    private fun sosTarget(): String? = openChat ?: (link.state as? State.Connected)?.peer?.address

    private fun confirmSos() {
        val peer = sosTarget()
        if (peer == null) {
            toast(R.string.sos_no_peer)
            return
        }
        val title = if (peer == openChat) chatTitle()
        else (link.state as? State.Connected)?.peer?.title ?: peer
        AlertDialog.Builder(this)
            .setTitle(R.string.sos_confirm_title)
            .setMessage(
                getString(if (groupIdFromKey(peer) != null) R.string.sos_group_confirm_text else R.string.sos_confirm_text, title)
            )
            .setPositiveButton(R.string.sos_send) { _, _ -> sendSos(peer) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sendSos(peer: String) {
        // Тревога уходит сразу — с последней известной точкой (получатель увидит её возраст),
        // а следом отдельным сообщением — свежие координаты.
        val groupId = groupIdFromKey(peer)
        val id = if (groupId != null) link.sendGroupSos(groupId, getString(R.string.sos_default_text), location.lastKnown())
        else link.sendSos(peer, getString(R.string.sos_default_text), location.lastKnown())
        val st = link.state
        // В группе «ушло сразу» — если хоть кто-то из участников на связи; остальным SOS уйдёт при подключении
        val queued = if (groupId != null) openGroupOnline == 0 else !(st is State.Connected && st.peer.address == peer)
        if (groupId == null && st is State.Idle) link.connect(peer)
        // Честно говорим, что произошло: ушло сразу или ждёт связи, и есть ли координаты
        val locationOff = location.hasPermission() && !location.isEnabled()
        toast(
            when {
                queued && locationOff -> R.string.sos_queued_location_off
                queued -> R.string.sos_queued
                locationOff -> R.string.sos_sent_location_off
                else -> R.string.sos_sent
            }
        )
        // Свежая точка обновляет это же сообщение, а не приходит отдельным
        val followUp = {
            location.requestFix { geo ->
                when {
                    geo == null -> toast(R.string.geo_failed)
                    groupId != null -> link.refreshGroupSosGeo(groupId, id, geo)
                    else -> link.refreshSosGeo(peer, id, geo)
                }
            }
            Unit
        }
        when {
            !location.hasPermission() -> requestLocationPermission(followUp)
            !locationOff -> followUp()
        }
    }

    private fun showSosDialog(m: ChatMessage, peer: BtLink.PeerInfo) {
        val groupName = m.groupId?.let { gid -> groupSummaries.firstOrNull { it.info.id == gid }?.info?.name }
        val b = AlertDialog.Builder(this)
            .setTitle(
                if (groupName != null) getString(R.string.sos_from_group, peer.title, groupName)
                else getString(R.string.sos_from, peer.title)
            )
            .setMessage(Format.sosSummary(this, m))
            .setNeutralButton(R.string.open_chat) { _, _ -> openChatScreen(m.peer) } // для группы m.peer — её ключ
            .setNegativeButton(R.string.close, null)
        val g = m.geo
        if (g != null) b.setPositiveButton(R.string.geo_direction) { _, _ -> showDirection(g, peer.title, m.sentAt) }
        val d = b.show()
        sosDialog = d
        sosDialogId = m.id
        d.setOnDismissListener { if (sosDialog === d) { sosDialog = null; sosDialogId = null } }
    }

    private var sosDialog: AlertDialog? = null
    private var sosDialogId: String? = null

    // ---------- Меню и диалоги ----------

    private fun showMenu() {
        val popup = PopupMenu(this, btnMore)
        val menu = popup.menu
        val st = link.state
        val chat = openChat
        if (chat != null && groupIdFromKey(chat) != null) {
            menu.add(0, M_GROUP_MEMBERS, 0, R.string.menu_group_members)
            menu.add(0, M_GROUP_CONNECT, 1, R.string.menu_group_connect)
            menu.add(0, M_SOS, 2, R.string.menu_sos)
            menu.add(0, M_CLEAR, 3, R.string.menu_clear_history)
            menu.add(0, M_GROUP_LEAVE, 4, R.string.menu_leave_group)
        } else if (chat != null) {
            val linked = st.peerAddress == chat && st !is State.IncomingRequest
            if (linked) menu.add(0, M_DISCONNECT, 0, R.string.menu_disconnect)
            else menu.add(0, M_CONNECT, 0, R.string.menu_connect)
            menu.add(0, M_SOS, 1, R.string.menu_sos)
            menu.add(0, M_CLEAR, 2, R.string.menu_clear_history)
        } else {
            menu.add(0, M_NEW_GROUP, 0, R.string.menu_new_group)
            menu.add(0, M_VISIBLE, 0, R.string.menu_visible)
            menu.add(0, M_REFRESH, 1, R.string.menu_refresh)
            if (st.peerAddress != null && st !is State.IncomingRequest) menu.add(0, M_DISCONNECT, 2, R.string.menu_disconnect)
            if (st is State.Connected) menu.add(0, M_SOS, 3, R.string.menu_sos)
            menu.add(0, M_SHOW_ALL, 4, R.string.menu_show_all).apply {
                isCheckable = true
                isChecked = prefs.showAllDevices
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) menu.add(0, M_ALARM, 5, R.string.menu_alarm_settings)
            menu.add(0, M_RELAY, 5, R.string.menu_relay)
            menu.add(0, M_STILL, 5, R.string.menu_still)
            menu.add(0, M_PROFILE, 5, R.string.menu_profile)
            menu.add(0, M_EXIT, 6, R.string.menu_exit)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                M_CONNECT -> chat?.let { addr -> withBluetooth { link.connect(addr) } }
                M_DISCONNECT -> link.disconnect()
                M_SOS -> confirmSos()
                M_CLEAR -> chat?.let { confirmClear(it) }
                M_NEW_GROUP -> showNewGroupDialog()
                M_GROUP_MEMBERS -> groupIdFromKey(chat)?.let { showGroupMembers(it) }
                M_GROUP_CONNECT -> groupIdFromKey(chat)?.let { connectGroupMembers(it, auto = false) }
                M_GROUP_LEAVE -> groupIdFromKey(chat)?.let { confirmLeaveGroup(it, chatTitle()) }
                M_VISIBLE -> makeDiscoverable()
                M_REFRESH -> withBluetooth {
                    loadBonded()
                    reloadHome()
                }
                M_SHOW_ALL -> {
                    prefs.showAllDevices = !prefs.showAllDevices
                    rebuildHomeRows()
                }
                M_ALARM -> showAlarmDndDialog()
                M_PROFILE -> showProfileDialog(first = false)
                M_RELAY -> showRelayDialog()
                M_STILL -> showStillDialog()
                M_EXIT -> exitApp()
            }
            true
        }
        popup.show()
    }

    private fun confirmClear(peer: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_text)
            .setPositiveButton(R.string.delete) { _, _ -> link.clearHistory(peer) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exitApp() {
        stopService(Intent(this, LinkService::class.java))
        link.stop()
        finish()
    }

    private fun showProfileDialog(first: Boolean) {
        val v = layoutInflater.inflate(R.layout.dialog_profile, null)
        val name = v.findViewById<EditText>(R.id.profileName)
        val role = v.findViewById<EditText>(R.id.profileRole)
        name.setText(prefs.userName)
        role.setText(prefs.userRole)
        AlertDialog.Builder(this)
            .setTitle(if (first) R.string.profile_title_first else R.string.profile_title)
            .setView(v)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.userName = name.text.toString()
                prefs.userRole = role.text.toString()
                if (!first) toast(R.string.profile_saved)
            }
            .setNegativeButton(if (first) R.string.later else R.string.cancel, null)
            .setOnDismissListener {
                if (first) {
                    prefs.profileAsked = true
                    withBluetooth(action = null)
                }
            }
            .show()
    }

    /** Настройки пересылки: общий выключатель, пересылка чужих групп и счётчик пересланного. */
    private fun showRelayDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_relay, null)
        val enable = v.findViewById<CheckBox>(R.id.relayEnable)
        val foreign = v.findViewById<CheckBox>(R.id.relayForeign)
        val count = v.findViewById<TextView>(R.id.relayCount)
        enable.isChecked = prefs.relayEnabled
        foreign.isChecked = prefs.relayForeignGroups
        foreign.isEnabled = enable.isChecked // при выключенной пересылке чужие группы тоже не пересылаются
        enable.setOnCheckedChangeListener { _, on -> foreign.isEnabled = on }
        count.text = getString(R.string.relay_count, prefs.relayedCount)
        AlertDialog.Builder(this)
            .setTitle(R.string.relay_title)
            .setView(v)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.relayEnabled = enable.isChecked
                prefs.relayForeignGroups = foreign.isChecked
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.relay_reset_count) { _, _ -> prefs.resetRelayedCount() }
            .show()
    }

    // ---------- Контроль неподвижности («человек не двигается») ----------

    private fun stillLabel(sec: Int): String =
        if (sec % 60 == 0) getString(R.string.still_min_fmt, sec / 60) else getString(R.string.still_sec_fmt, sec)

    private fun nearestIndex(options: List<Int>, value: Int): Int =
        options.indices.minByOrNull { Math.abs(options[it] - value) } ?: 0

    private fun showStillDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_still, null)
        val enable = v.findViewById<CheckBox>(R.id.stillEnable)
        val after = v.findViewById<Spinner>(R.id.stillAfter)
        val reply = v.findViewById<Spinner>(R.id.stillReply)
        val surface = v.findViewById<CheckBox>(R.id.stillSurface)
        after.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, STILL_AFTER_OPTIONS.map { stillLabel(it) })
        reply.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, STILL_REPLY_OPTIONS.map { stillLabel(it) })
        after.setSelection(nearestIndex(STILL_AFTER_OPTIONS, prefs.stillSeconds))
        reply.setSelection(nearestIndex(STILL_REPLY_OPTIONS, prefs.stillReplySeconds))
        enable.isChecked = prefs.stillEnabled
        surface.isChecked = prefs.stillIgnoreSurface
        val sync = { on: Boolean ->
            after.isEnabled = on
            reply.isEnabled = on
            surface.isEnabled = on
        }
        sync(enable.isChecked)
        enable.setOnCheckedChangeListener { _, on -> sync(on) }
        AlertDialog.Builder(this)
            .setTitle(R.string.still_title)
            .setView(v)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.stillEnabled = enable.isChecked
                prefs.stillSeconds = STILL_AFTER_OPTIONS[after.selectedItemPosition]
                prefs.stillReplySeconds = STILL_REPLY_OPTIONS[reply.selectedItemPosition]
                prefs.stillIgnoreSurface = surface.isChecked
                applyStillSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Датчик запускает и останавливает служба; здесь только готовим разрешения и даём службе перечитать настройки. */
    private fun applyStillSettings() {
        if (!prefs.stillEnabled) {
            if (link.state !is State.Off) startLink() // служба перечитает настройки и остановит датчик
            return
        }
        // предупреждение — это уведомление: без него функция не включится (SOS без предупреждения не отправляем)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            pendingStillSetup = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        continueStillSetup()
    }

    private fun continueStillSetup() {
        if (!core.notifier.canWarn()) toast(R.string.still_need_notifications)
        withBluetooth { } // связь и служба нужны, чтобы датчик работал в фоне
        // геопозиция нужна, чтобы SOS ушёл с координатами; после выдачи служба перезапускается с типом location
        if (!location.hasPermission()) requestLocationPermission { withBluetooth { } }
    }

    private fun showPermissionSettingsDialog(@StringRes text: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.permissions_title)
            .setMessage(text)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                } catch (e: ActivityNotFoundException) {
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLocationSettingsDialog(@StringRes text: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.location_off_title)
            .setMessage(text)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (e: ActivityNotFoundException) {
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(@StringRes res: Int) = toast(getString(res))

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
