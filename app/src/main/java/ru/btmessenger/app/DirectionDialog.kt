package ru.btmessenger.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * «Направление и расстояние» до точки из сообщения. Работает без интернета:
 * расстояние и азимут считаются по двум GPS-координатам, стрелка — по компасу телефона.
 */
class DirectionDialog(
    private val activity: Activity,
    private val target: GeoPoint,
    private val title: String,
    private val sentAt: Long
) : SensorEventListener {

    private val location = LocationHelper(activity.applicationContext)
    private val sensors = activity.getSystemService(SensorManager::class.java)
    private val rotationSensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private lateinit var arrow: DirectionView
    private lateinit var distanceText: TextView
    private lateinit var bearingText: TextView
    private lateinit var hintText: TextView

    private var compassAvailable = false
    private var stopUpdates: () -> Unit = {}
    private var me: Location? = null
    private var azimuth: Float? = null // направление верха экрана, градусы от истинного севера
    private val rot = FloatArray(9)
    private val orient = FloatArray(3)

    fun show() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_direction, null)
        arrow = view.findViewById(R.id.directionArrow)
        distanceText = view.findViewById(R.id.directionDistance)
        bearingText = view.findViewById(R.id.directionBearing)
        hintText = view.findViewById(R.id.directionHint)

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.geo_open_maps) { _, _ -> MapLinks.openGeo(activity, target, title) }
            .setOnDismissListener { stop() }
            .show()

        compassAvailable = rotationSensor != null &&
            sensors?.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI) == true
        stopUpdates = location.startUpdates { l ->
            me = l
            update()
        }
        update()
    }

    private fun stop() {
        sensors?.unregisterListener(this)
        stopUpdates()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        SensorManager.getOrientation(rot, orient)
        var az = Math.toDegrees(orient[0].toDouble()).toFloat() + displayRotation()
        me?.let { l ->
            // компас показывает магнитный север, азимут по GPS — от истинного: учитываем склонение
            az += GeomagneticField(
                l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()
            ).declination
        }
        az = norm(az)
        val prev = azimuth
        azimuth = if (prev == null) az else smooth(prev, az)
        update()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun displayRotation(): Float {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.rotation
        }
        return when (r) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> 270f
            else -> 0f
        }
    }

    private fun update() {
        val l = me
        val stale = sentAt - target.fixTime > Format.STALE_FIX_MS
        val targetInfo = buildString {
            append(activity.getString(R.string.direction_target_acc, Format.accuracyOrUnknown(activity, target.accuracy)))
            if (stale) {
                if (isNotEmpty()) append('\n')
                append(activity.getString(R.string.geo_stale, Format.duration(activity, sentAt - target.fixTime)))
            }
        }
        if (l == null) {
            arrow.hasDirection = false
            distanceText.text = activity.getString(R.string.direction_locating)
            bearingText.text = Format.coords(target)
            hintText.text = targetInfo
            return
        }
        val res = FloatArray(2)
        Location.distanceBetween(l.latitude, l.longitude, target.lat, target.lon, res)
        val bearing = norm(res[1])
        distanceText.text = Format.distance(activity, res[0])
        bearingText.text = activity.getString(
            R.string.direction_bearing, bearing.toInt(), Format.compassPoint(activity, bearing)
        )
        val az = azimuth
        // Без компаса стрелку не рисуем: «север вверху» легко принять за настоящее направление
        arrow.hasDirection = az != null
        if (az != null) arrow.arrowAngle = bearing - az
        val hints = mutableListOf<String>()
        if (az == null) {
            hints += activity.getString(if (compassAvailable) R.string.direction_compass_wait else R.string.direction_no_compass)
        }
        if (l.hasAccuracy()) hints += activity.getString(R.string.direction_my_acc, l.accuracy.toInt().coerceAtLeast(1))
        if (targetInfo.isNotEmpty()) hints += targetInfo
        if (compassAvailable) hints += activity.getString(R.string.direction_calibrate)
        hintText.text = hints.joinToString("\n")
    }

    private fun norm(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    private fun smooth(prev: Float, next: Float): Float {
        val diff = ((next - prev + 540f) % 360f) - 180f
        return norm(prev + diff * 0.2f)
    }
}

/** Круг со стрелкой, повёрнутой на [arrowAngle] градусов от верха экрана. */
class DirectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var arrowAngle: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var hasDirection: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = ContextCompat.getColor(context, R.color.tg_secondary)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.tg_accent)
    }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.tg_search)
    }
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = (200 * density).toInt()
        val w = resolveSize(want, widthMeasureSpec)
        val h = resolveSize(want, heightMeasureSpec)
        val size = min(min(w, h), want)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 4f * density
        canvas.drawCircle(cx, cy, r, ringPaint)
        if (!hasDirection) {
            canvas.drawCircle(cx, cy, r * 0.25f, idlePaint)
            return
        }
        canvas.save()
        canvas.rotate(arrowAngle, cx, cy)
        path.reset()
        path.moveTo(cx, cy - r * 0.8f)
        path.lineTo(cx + r * 0.42f, cy + r * 0.6f)
        path.lineTo(cx, cy + r * 0.32f)
        path.lineTo(cx - r * 0.42f, cy + r * 0.6f)
        path.close()
        canvas.drawPath(path, arrowPaint)
        canvas.restore()
    }
}
