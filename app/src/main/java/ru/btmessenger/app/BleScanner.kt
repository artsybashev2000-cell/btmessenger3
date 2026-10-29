package ru.btmessenger.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock

/**
 * Кого и когда мы слышали по BLE. Чистая логика без Android, чтобы проверять тестами.
 * Ключ — короткий id из объявления ([BleAdvert.key]); адрес BLE у многих телефонов случайный и меняется.
 */
class BleSightings(private val ttlMs: Long = TTL_MS) {
    data class Sighting(val key: String, val bleAddress: String, val rssi: Int, val seenAt: Long)

    private val map = HashMap<String, Sighting>()

    /** @return true, если в списке «рядом» что-то изменилось (новый телефон или заметно другой сигнал). */
    fun update(key: String, bleAddress: String, rssi: Int, now: Long): Boolean {
        val old = map[key]
        val fresh = old != null && now - old.seenAt <= ttlMs
        // немного сглаживаем RSSI: он «прыгает» на несколько дБ от пакета к пакету
        val smooth = if (fresh) (old!!.rssi * 2 + rssi) / 3 else rssi
        map[key] = Sighting(key, bleAddress, smooth, now)
        lastFresh = lastFresh + key
        return !fresh || old!!.bleAddress != bleAddress || kotlin.math.abs(old.rssi - smooth) >= RSSI_STEP
    }

    /** Те, кого слышали недавно: они «рядом». */
    fun fresh(now: Long): List<Sighting> = map.values.filter { now - it.seenAt <= ttlMs }

    fun get(key: String, now: Long): Sighting? = map[key]?.takeIf { now - it.seenAt <= ttlMs }

    /**
     * Проверить, кто перестал быть «рядом», и забыть совсем давних.
     * @return true, если кто-то только что пропал из «рядом» (экран надо обновить).
     */
    fun prune(now: Long): Boolean {
        val current = fresh(now).mapTo(HashSet()) { it.key }
        val lost = lastFresh.any { it !in current }
        lastFresh = current
        map.values.removeAll { now - it.seenAt > ttlMs * 2 }
        return lost
    }

    private var lastFresh: Set<String> = emptySet()

    companion object {
        /** Объявление идёт примерно раз в секунду, а сканер в экономном режиме слушает не всё время. */
        const val TTL_MS = 30_000L
        const val RSSI_STEP = 4
    }
}

/**
 * Сканер BLE-объявлений BtMessenger (см. [BleAdvert]). Фильтр по UUID службы: посторонние устройства не видны.
 * Работает, пока открыт экран приложения. Все вызовы и обратные вызовы идут в главном потоке.
 */
class BleScanner(private val ctx: Context, private val prefs: Prefs) {
    private val handler = Handler(Looper.getMainLooper())
    private val sightings = BleSightings()
    private var callback: ScanCallback? = null
    private var pending = false

    /** Список «рядом» изменился; вызывается не чаще раза в секунду. */
    var onChanged: (() -> Unit)? = null

    val isRunning: Boolean get() = callback != null

    private val ownKey: String get() = BleAdvert.key(prefs.installId)

    private val notifyRunnable = Runnable {
        pending = false
        onChanged?.invoke()
    }

    private val pruneRunnable = object : Runnable {
        override fun run() {
            if (sightings.prune(now())) onChanged?.invoke()
            if (callback != null) handler.postDelayed(this, PRUNE_MS)
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun changed() {
        if (pending) return
        pending = true
        handler.postDelayed(notifyRunnable, NOTIFY_MS)
    }

    /** Телефоны с BtMessenger, которых слышали недавно (у них короткий id, адрес BLE и сигнал). */
    fun nearby(): List<BleSightings.Sighting> = sightings.fresh(now())

    /** Сигнал знакомого по id установки; null — его сейчас не слышно. */
    fun rssiOf(installId: String): Int? = sightings.get(BleAdvert.key(installId), now())?.rssi

    @SuppressLint("MissingPermission")
    fun start() {
        if (callback != null) return
        if (!hasPermission()) return // Android 12+: BLUETOOTH_SCAN; 6-11: геопозиция. Сами не спрашиваем: это делает экран
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleAdvert.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED) // компромисс: найти за несколько секунд и не посадить батарею
            .setReportDelay(0)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handle(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handle(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (callback === this) callback = null // например, слишком частые запуски: экран попробует при следующем onStart
            }
        }
        callback = cb
        try {
            scanner.startScan(listOf(filter), settings, cb)
            handler.postDelayed(pruneRunnable, PRUNE_MS)
        } catch (e: SecurityException) {
            callback = null
        } catch (e: IllegalStateException) {
            callback = null
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacks(pruneRunnable)
        handler.removeCallbacks(notifyRunnable)
        pending = false
        val cb = callback ?: return
        callback = null
        try {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: Exception) {
            // Bluetooth выключен или разрешение отозвано: сканирование и так остановлено
        }
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(BleAdvert.MANUFACTURER_ID)
        val id = BleAdvert.parseShortId(data) ?: return // чужой формат
        val key = BleAdvert.hex(id)
        if (key == ownKey) return
        if (sightings.update(key, result.device.address, result.rssi, now())) changed()
    }

    private fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    private companion object {
        const val NOTIFY_MS = 1_000L
        const val PRUNE_MS = 5_000L
    }
}
