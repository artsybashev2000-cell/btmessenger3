package ru.btmessenger.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.security.MessageDigest
import java.util.UUID

/**
 * Что именно мы кладём в BLE-объявление и сколько оно занимает.
 *
 * Обычное объявление BLE — не больше 31 байта. Считаем:
 * - флаги (система может добавить сама): 3 байта;
 * - 128-битный UUID службы: 2 (длина и тип) + 16 = 18 байт;
 * - данные производителя: 2 (длина и тип) + 2 (код производителя) + 5 (наши данные) = 9 байт.
 * Итого 30 из 31. Имя устройства и мощность передатчика в объявление не кладём — не помещаются.
 *
 * Наши 5 байт: версия формата (1) + короткий id (4). Короткий id — первые 4 байта SHA-256 от id установки;
 * по нему телефон, который нас нашёл, потом сопоставит нас с собеседником из рукопожатия (часть 2).
 */
object BleAdvert {
    /** UUID службы приложения; тот же, что у RFCOMM-службы, чтобы не плодить идентификаторы. */
    val SERVICE_UUID: UUID = Protocol.APP_UUID

    /** 0xFFFF — код для внутренних и тестовых нужд, своего зарегистрированного у приложения нет. */
    const val MANUFACTURER_ID = 0xFFFF

    const val PAYLOAD_VERSION = 1
    const val SHORT_ID_BYTES = 4

    const val LEGACY_LIMIT = 31
    private const val FLAGS_BYTES = 3
    private const val UUID128_FIELD_BYTES = 2 + 16
    private const val MANUFACTURER_HEADER_BYTES = 2 + 2

    fun shortId(installId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(installId.toByteArray(Charsets.UTF_8)).copyOf(SHORT_ID_BYTES)

    /** Данные производителя: версия формата + короткий id. */
    fun payload(installId: String): ByteArray = byteArrayOf(PAYLOAD_VERSION.toByte()) + shortId(installId)

    /** Сколько байт займёт всё объявление (с запасом на флаги). */
    fun encodedSize(installId: String): Int =
        FLAGS_BYTES + UUID128_FIELD_BYTES + MANUFACTURER_HEADER_BYTES + payload(installId).size

    /** Короткий id в виде строки: по ней сканер находит знакомых и склеивает повторные объявления. */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun key(installId: String): String = hex(shortId(installId))

    /** Для сканера (часть 2): достаёт короткий id из данных производителя, null — чужой или неизвестный формат. */
    fun parseShortId(manufacturerData: ByteArray?): ByteArray? {
        if (manufacturerData == null || manufacturerData.size != 1 + SHORT_ID_BYTES) return null
        if (manufacturerData[0].toInt() != PAYLOAD_VERSION) return null
        return manufacturerData.copyOfRange(1, manufacturerData.size)
    }
}

/**
 * Постоянное, экономное BLE-объявление «я BtMessenger». Работает вместе с [LinkService].
 * Все методы вызываются из главного потока (служба и обратные вызовы BLE так и работают).
 */
class BleAdvertiser(private val ctx: Context, private val prefs: Prefs) {

    private val handler = Handler(Looper.getMainLooper())
    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null
    private var wanted = false
    private var failures = 0

    /** Объявление сейчас рассылается. */
    @Volatile
    var isActive = false
        private set

    private val retry = Runnable { if (wanted) start() }

    /** Включить или выключить объявление в зависимости от состояния связи. */
    fun sync(active: Boolean) {
        wanted = active
        if (active) start() else stop()
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (callback != null) return // уже запущено
        if (!hasPermission()) return // Android 12+: BLUETOOTH_ADVERTISE; после выдачи разрешения служба вызовет sync снова
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        // null — телефон не умеет рассылать объявления (нет поддержки в чипе) или Bluetooth выключен
        val adv = adapter.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER) // интервал около секунды
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false) // подключаться к объявлению не нужно: дальше работает RFCOMM
            .setTimeout(0) // без ограничения по времени
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleAdvert.SERVICE_UUID))
            .addManufacturerData(BleAdvert.MANUFACTURER_ID, BleAdvert.payload(prefs.installId))
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (callback !== this) return
                failures = 0
                isActive = true
            }

            override fun onStartFailure(errorCode: Int) {
                if (callback !== this) return
                callback = null
                isActive = false
                when (errorCode) {
                    // телефон не умеет или данные не влезают — повторять бесполезно
                    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED, AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> Unit
                    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> isActive = true
                    // слишком много объявлений у других приложений, сбой стека: пробуем позже
                    else -> if (wanted && ++failures <= MAX_RETRIES) handler.postDelayed(retry, RETRY_MS)
                }
            }
        }
        callback = cb
        advertiser = adv
        try {
            adv.startAdvertising(settings, data, cb)
        } catch (e: SecurityException) {
            callback = null // разрешение отозвали
        } catch (e: IllegalStateException) {
            callback = null // Bluetooth выключили в момент запуска
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        wanted = false
        handler.removeCallbacks(retry)
        val cb = callback
        callback = null
        isActive = false
        if (cb != null) {
            try {
                advertiser?.stopAdvertising(cb)
            } catch (e: Exception) {
                // Bluetooth уже выключен или нет разрешения — объявление и так остановлено
            }
        }
        advertiser = null
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MAX_RETRIES = 5
        const val RETRY_MS = 30_000L
    }
}
