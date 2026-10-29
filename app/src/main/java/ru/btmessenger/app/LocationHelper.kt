package ru.btmessenger.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Получение геопозиции без сторонних библиотек.
 *
 * Раньше при неудаче отправлялась getLastKnownLocation без пометки — она могла быть
 * многочасовой давности. Теперь у точки всегда есть точность и честное время замера,
 * а старая точка помечается в чате как устаревшая.
 */
@SuppressLint("MissingPermission")
class LocationHelper(private val ctx: Context) {
    private val lm: LocationManager? = ctx.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    fun hasPermission(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Включена ли геолокация в настройках телефона. */
    fun isEnabled(): Boolean {
        val m = lm ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            m.isLocationEnabled
        } else {
            providerEnabled(m, LocationManager.GPS_PROVIDER) || providerEnabled(m, LocationManager.NETWORK_PROVIDER)
        }
    }

    /** Есть ли хоть один включённый источник, который приложению разрешено использовать. */
    fun hasActiveProvider(): Boolean {
        val m = lm ?: return false
        return hasPermission() && activeProviders(m).isNotEmpty()
    }

    private fun providerEnabled(m: LocationManager, p: String): Boolean = try {
        m.isProviderEnabled(p)
    } catch (e: Exception) {
        false
    }

    private fun activeProviders(m: LocationManager): List<String> {
        val list = mutableListOf<String>()
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) && providerEnabled(m, LocationManager.GPS_PROVIDER)) {
            list += LocationManager.GPS_PROVIDER
        }
        if (providerEnabled(m, LocationManager.NETWORK_PROVIDER)) list += LocationManager.NETWORK_PROVIDER
        return list
    }

    /**
     * Одна точка: ждём точность лучше [GOOD_ACCURACY_M]; через [SOFT_TIMEOUT_MS] берём лучшую из
     * полученных; через [HARD_TIMEOUT_MS] — последнюю известную (с её настоящим возрастом).
     * [callback] вызывается на главном потоке ровно один раз (null — определить не удалось).
     */
    fun requestFix(callback: (GeoPoint?) -> Unit): Request? {
        val m = lm
        val providers = if (m != null && hasPermission()) activeProviders(m) else emptyList()
        if (m == null || providers.isEmpty()) {
            main.post { callback(null) }
            return null
        }
        return Request(m, providers, callback).also { it.start() }
    }

    /** Последняя известная точка (может быть старой — возраст сохраняется в GeoPoint). */
    fun lastKnown(): GeoPoint? {
        val m = lm ?: return null
        if (!hasPermission()) return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p ->
                try {
                    m.getLastKnownLocation(p)
                } catch (e: SecurityException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.toGeo()
    }

    /** Непрерывные обновления (для экрана «направление»). Возвращает функцию остановки. */
    fun startUpdates(onLocation: (Location) -> Unit): () -> Unit {
        val m = lm ?: return {}
        if (!hasPermission()) return {}
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)

            // На Android 10 и ниже эти методы абстрактные — без них возможен AbstractMethodError
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        for (p in activeProviders(m)) {
            try {
                m.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
            } catch (e: IllegalArgumentException) {
            }
        }
        lastKnownLocation(m)?.let { onLocation(it) }
        return {
            try {
                m.removeUpdates(listener)
            } catch (e: Exception) {
            }
        }
    }

    private fun lastKnownLocation(m: LocationManager): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p ->
                try {
                    m.getLastKnownLocation(p)
                } catch (e: Exception) {
                    null
                }
            }
            .filter { ageMs(it) < 5 * 60_000L }
            .maxByOrNull { it.elapsedRealtimeNanos }

    inner class Request internal constructor(
        private val m: LocationManager,
        private val providers: List<String>,
        private val callback: (GeoPoint?) -> Unit
    ) : LocationListener {
        private var best: Location? = null
        private var done = false
        private val soft = Runnable { best?.let { finish(it) } }
        private val hard = Runnable { finish(best) }

        fun start() {
            for (p in providers) {
                try {
                    m.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper())
                } catch (e: SecurityException) {
                } catch (e: IllegalArgumentException) {
                }
            }
            main.postDelayed(soft, SOFT_TIMEOUT_MS)
            main.postDelayed(hard, HARD_TIMEOUT_MS)
        }

        fun cancel() {
            if (done) return
            done = true
            cleanup()
        }

        override fun onLocationChanged(location: Location) {
            if (done) return
            if (ageMs(location) > 60_000L) return // провайдер отдал старую точку
            val b = best
            if (b == null || accuracyOf(location) < accuracyOf(b)) best = location
            if (location.hasAccuracy() && location.accuracy <= GOOD_ACCURACY_M) finish(location)
        }

        private fun finish(location: Location?) {
            if (done) return
            done = true
            cleanup()
            callback(location?.toGeo() ?: lastKnown())
        }

        private fun cleanup() {
            main.removeCallbacks(soft)
            main.removeCallbacks(hard)
            try {
                m.removeUpdates(this)
            } catch (e: Exception) {
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun accuracyOf(l: Location): Float = if (l.hasAccuracy()) l.accuracy else Float.MAX_VALUE

    private fun ageMs(l: Location): Long = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L

    private fun Location.toGeo(): GeoPoint = GeoPoint(
        lat = latitude,
        lon = longitude,
        accuracy = if (hasAccuracy()) accuracy else null,
        fixTime = System.currentTimeMillis() - ageMs(this).coerceAtLeast(0)
    )

    companion object {
        const val GOOD_ACCURACY_M = 25f
        const val SOFT_TIMEOUT_MS = 8_000L
        const val HARD_TIMEOUT_MS = 20_000L
    }
}
