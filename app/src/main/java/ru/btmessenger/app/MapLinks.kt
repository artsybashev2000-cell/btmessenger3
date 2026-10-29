package ru.btmessenger.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.Locale

/** Открытие точки на карте. */
object MapLinks {

    /**
     * Универсальная ссылка geo: — её открывают любые карты, в том числе офлайн
     * (Organic Maps, OsmAnd, MAPS.ME с заранее скачанными картами). Это важно там,
     * где нет интернета: в лесу или на удалённом участке.
     */
    fun openGeo(ctx: Context, g: GeoPoint, label: String) {
        val uri = Uri.parse(
            String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", g.lat, g.lon, g.lat, g.lon, Uri.encode(label))
        )
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (intent.resolveActivity(ctx.packageManager) == null) {
            Toast.makeText(ctx, R.string.no_map_app, Toast.LENGTH_LONG).show()
            openYandex(ctx, g)
            return
        }
        try {
            ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.choose_map_app)))
        } catch (e: ActivityNotFoundException) {
            openYandex(ctx, g)
        }
    }

    /** Яндекс Карты: приложение, если установлено, иначе сайт (нужен интернет). */
    fun openYandex(ctx: Context, g: GeoPoint) {
        val pt = String.format(Locale.US, "%.6f,%.6f", g.lon, g.lat)
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("yandexmaps://maps.yandex.ru/?pt=$pt&z=17&l=map")))
        } catch (e: ActivityNotFoundException) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/maps/?pt=$pt&z=17&l=map")))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(ctx, R.string.map_open_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun copy(ctx: Context, g: GeoPoint) {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.geo_title), Format.coords(g)))
        Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
