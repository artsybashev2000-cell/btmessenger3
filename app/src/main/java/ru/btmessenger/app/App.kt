package ru.btmessenger.app

import android.app.Application
import android.content.Context

/** Общие объекты приложения: живут дольше экрана, поэтому связь не зависит от Activity. */
class App : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var repo: ChatRepository
        private set
    lateinit var notifier: Notifier
        private set
    lateinit var link: BtLink
        private set

    /** Сканер BLE-объявлений других телефонов (работает, пока открыт экран). */
    lateinit var ble: BleScanner
        private set

    /** Проигрыватель входящей рации. Им управляет [LinkService], поэтому рация слышна и при выключенном экране. */
    lateinit var live: LivePlayer
        private set

    /** Мы сейчас говорим в рацию: входящую рацию не играем (иначе эхо и «гонки» двух передач). */
    @Volatile
    var radioTx = false

    /** Экран приложения сейчас на виду. */
    @Volatile
    var uiVisible = false

    /** Почему фоновая служба не запустилась (R.string.*, 0 — всё в порядке); экран покажет это пользователю. */
    @Volatile
    var serviceProblem = 0

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = ChatRepository(this)
        notifier = Notifier(this).also { it.createChannels() }
        link = BtLink(this, prefs, repo)
        ble = BleScanner(this, prefs)
        live = LivePlayer(this)
    }
}

val Context.core: App get() = applicationContext as App
