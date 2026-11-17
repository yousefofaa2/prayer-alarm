package com.example.prayeralarm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.provider.AlarmClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.Qibla
import com.batoulapps.adhan.data.DateComponents
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class MainActivity : Activity(), SensorEventListener {

    private val NAVY = Color.parseColor("#0A1640")
    private val CARD = Color.parseColor("#132660")
    private val GOLD = Color.parseColor("#F2C14E")
    private val CREAM = Color.parseColor("#FFF4D6")
    private val DIM = Color.parseColor("#9FB0DA")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var content: FrameLayout
    private val tabViews = ArrayList<TextView>()
    private var tab = 0
    private var openSurah = -1

    private var lat = 30.0444
    private var lng = 31.2357
    private var hasLocation = false

    private var surahs: JSONArray? = null
    private var quranFailed = false

    private var compass: CompassView? = null
    private var hint: TextView? = null
    private var azSmooth = 0f
    private var qiblaDeg = 0.0
    private var declination = 0f

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NAVY
        window.navigationBarColor = NAVY
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        loadSavedLocation()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NAVY)
        }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bar = LinearLayout(this).apply { setBackgroundColor(CARD) }
        val names = listOf("🕌  المواقيت", "🧭  القبلة", "📖  المصحف")
        names.forEachIndexed { i, n ->
            val t = label(n, 15f, DIM, true)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(14), 0, dp(14))
            t.setOnClickListener { openSurah = -1; show(i) }
            tabViews.add(t)
            bar.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)

        requestLocationIfNeeded()
        loadQuranAsync()
        show(0)
    }

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab == 2 && openSurah >= 0) {
            openSurah = -1
            show(2)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun shape(color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun lp(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(top), 0, dp(bottom)) }

    private fun arabicDigits(s: String): String =
        s.map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")

    private fun fmt(d: Date): String =
        arabicDigits(SimpleDateFormat("hh:mm a", Locale("ar")).format(d))

    private fun page(): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }
        sv.addView(col)
        return Pair(sv, col)
    }

    private fun show(i: Int) {
        tab = i
        content.removeAllViews()
        compass = null
        hint = null
        tabViews.forEachIndexed { k, t -> t.setTextColor(if (k == i) GOLD else DIM) }
        content.addView(
            when (i) {
                0 -> timesView()
                1 -> qiblaView()
                else -> quranView()
            }
        )
    }

    // ---------------------------------------------------------------- location

    private fun loadSavedLocation() {
        val p = getSharedPreferences("loc", Context.MODE_PRIVATE)
        val la = p.getString("lat", null)?.toDoubleOrNull()
        val lo = p.getString("lng", null)?.toDoubleOrNull()
        if (la != null && lo != null) {
            lat = la
            lng = lo
            hasLocation = true
        }
    }

    private fun saveLocation(la: Double, lo: Double) {
        lat = la
        lng = lo
        hasLocation = true
        getSharedPreferences("loc", Context.MODE_PRIVATE).edit()
            .putString("lat", la.toString()).putString("lng", lo.toString()).apply()
    }

    private fun requestLocationIfNeeded() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            updateLocation()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        updateLocation()
        show(tab)
    }

    private fun updateLocation() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val best = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
            if (best != null) {
                saveLocation(best.latitude, best.longitude)
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, object : LocationListener {
                    override fun onLocationChanged(l: Location) {
                        saveLocation(l.latitude, l.longitude)
                        runOnUiThread { show(tab) }
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                }, mainLooper)
            }
        } catch (e: Exception) {
            // نكمل بالموقع المحفوظ أو الافتراضي
        }
    }

    // ---------------------------------------------------------------- prayer times

    private fun timesFor(d: Date): PrayerTimes =
        PrayerTimes(
            Coordinates(lat, lng),
            DateComponents.from(d),
            CalculationMethod.EGYPTIAN.parameters
        )

    private fun timesView(): View {
        val (sv, col) = page()
        val now = Date()
        val t = timesFor(now)
        val list = listOf(
            "الفجر" to t.fajr, "الظهر" to t.dhuhr, "العصر" to t.asr,
            "المغرب" to t.maghrib, "العشاء" to t.isha
        )
        val next = list.firstOrNull { it.second.after(now) }
            ?: ("الفجر" to timesFor(Date(now.time + 86400000L)).fajr)

        col.addView(label("منبه الصلاة", 28f, GOLD, true))
        col.addView(
            label(
                SimpleDateFormat("EEEE d MMMM yyyy", Locale("ar")).format(now),
                14f, DIM
            ), lp(2, 0)
        )
        col.addView(
            label(
                if (hasLocation) "المواقيت حسب موقعك"
                else "الموقع الافتراضي: القاهرة — اسمح بالموقع لمواقيت أدق",
                12f, DIM
            ), lp(0, 16)
        )

        val diffMin = ((next.second.time - now.time) / 60000L).toInt()
        val nextBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(CARD, 20, GOLD)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        nextBox.addView(label("الصلاة القادمة", 13f, DIM))
        nextBox.addView(label("${next.first}   ${fmt(next.second)}", 30f, GOLD, true))
        nextBox.addView(
            label(
                arabicDigits("بعد ${diffMin / 60} ساعة و ${diffMin % 60} دقيقة"),
                14f, CREAM
            )
        )
        col.addView(nextBox, lp(0, 16))

        list.forEach { (name, time) ->
            val isNext = name == next.first
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = shape(if (isNext) Color.parseColor("#1B3480") else CARD, 14)
                setPadding(dp(18), dp(16), dp(18), dp(16))
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(label(name, 20f, CREAM, isNext), LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(label(fmt(time), 20f, GOLD, true))
            col.addView(row, lp(0, 10))
        }
        col.addView(
            label("الشروق  ${fmt(t.sunrise)}", 13f, DIM).apply { gravity = Gravity.CENTER },
            lp(2, 16)
        )

        val status = label("", 14f, CREAM).apply { gravity = Gravity.CENTER }
        val btn = label("⏰  ضبط المنبهات في تطبيق الساعة", 17f, NAVY, true).apply {
            gravity = Gravity.CENTER
            background = shape(GOLD, 14)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { status.text = setAlarms(list) }
        }
        col.addView(btn, lp(0, 12))
        col.addView(status, lp(0, 0))
        col.addView(
            label(
                "المنبهات بتتضاف مرة واحدة لكل صلاة، فاضغط الزرار يوميًا لتحديثها.",
                12f, DIM
            ).apply { gravity = Gravity.CENTER }, lp(12, 0)
        )
        return sv
    }

    private fun setAlarms(list: List<Pair<String, Date>>): String {
        val sb = StringBuilder("تم ضبط المنبهات:\n")
        for ((name, date) in list) {
            val c = Calendar.getInstance().apply { time = date }
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, c.get(Calendar.HOUR_OF_DAY))
                putExtra(AlarmClock.EXTRA_MINUTES, c.get(Calendar.MINUTE))
                putExtra(AlarmClock.EXTRA_MESSAGE, "أذان $name")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            try {
                startActivity(intent)
                sb.append("$name  ${fmt(date)}\n")
            } catch (e: Exception) {
                sb.append("$name: تعذر الضبط\n")
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- qibla

    private fun qiblaView(): View {
        qiblaDeg = Qibla(Coordinates(lat, lng)).direction
        declination = GeomagneticField(lat.toFloat(), lng.toFloat(), 0f, System.currentTimeMillis()).declination

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }
        col.addView(label("اتجاه القبلة", 28f, GOLD, true))
        col.addView(
            label(arabicDigits("القبلة ${qiblaDeg.toInt()}° من الشمال"), 14f, DIM), lp(2, 16)
        )

        val cv = CompassView(this, GOLD, CARD, CREAM)
        cv.set(azSmooth, qiblaDeg.toFloat())
        compass = cv
        col.addView(cv, LinearLayout.LayoutParams(dp(300), dp(300)))

        val h = label("", 17f, CREAM, true).apply { gravity = Gravity.CENTER }
        hint = h
        col.addView(h, lp(20, 4))
        col.addView(
            label(
                "امسك التليفون أفقي ومسطّح. لو الاتجاه مش دقيق، حرّك التليفون على شكل رقم ٨ في الهوا.",
                12f, DIM
            ).apply { gravity = Gravity.CENTER }, lp(8, 0)
        )
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) == null) {
            h.text = "التليفون مفيهوش حساس بوصلة"
        }
        return col
    }

    override fun onSensorChanged(e: SensorEvent) {
        val c = compass ?: return
        val r = FloatArray(9)
        val o = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(r, e.values)
        SensorManager.getOrientation(r, o)
        var az = Math.toDegrees(o[0].toDouble()).toFloat() + declination
        az = (az + 360f) % 360f
        var d = az - azSmooth
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        azSmooth = (azSmooth + d * 0.2f + 360f) % 360f
        c.set(azSmooth, qiblaDeg.toFloat())
        val diff = abs(((qiblaDeg.toFloat() - azSmooth + 540f) % 360f) - 180f)
        hint?.text = if (diff < 4f) "✓ إنت ناحية القبلة" else "لف ناحية السهم الذهبي"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- quran

    private fun loadQuranAsync() {
        Thread {
            try {
                val text = assets.open("quran.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONObject(text).getJSONObject("data").getJSONArray("surahs")
                surahs = arr
                runOnUiThread { if (tab == 2) show(2) }
            } catch (e: Exception) {
                quranFailed = true
                runOnUiThread { if (tab == 2) show(2) }
            }
        }.start()
    }

    private fun quranView(): View {
        val data = surahs
        if (data == null) {
            val (sv, col) = page()
            col.addView(label("المصحف الشريف", 28f, GOLD, true))
            col.addView(
                label(
                    if (quranFailed) "تعذّر تحميل نص المصحف" else "جاري التحميل...",
                    16f, CREAM
                ), lp(16, 0)
            )
            return sv
        }
        return if (openSurah >= 0) readerView(data, openSurah) else surahList(data)
    }

    private fun surahList(data: JSONArray): View {
        val (sv, col) = page()
        col.addView(label("المصحف الشريف", 28f, GOLD, true))
        col.addView(label("اختر السورة للقراءة", 14f, DIM), lp(2, 14))
        for (i in 0 until data.length()) {
            val s = data.getJSONObject(i)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(CARD, 14)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                setOnClickListener { openSurah = i; show(2) }
            }
            val num = label(arabicDigits(s.getInt("number").toString()), 14f, GOLD, true).apply {
                gravity = Gravity.CENTER
                background = shape(NAVY, 20, GOLD)
            }
            row.addView(num, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(
                label(s.getString("name"), 20f, CREAM).apply { setPadding(dp(14), 0, dp(14), 0) },
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
            row.addView(
                label(arabicDigits("${s.getJSONArray("ayahs").length()} آية"), 12f, DIM)
            )
            col.addView(row, lp(0, 8))
        }
        return sv
    }

    private fun readerView(data: JSONArray, i: Int): View {
        val s = data.getJSONObject(i)
        val ayahs = s.getJSONArray("ayahs")
        val bismillah = data.getJSONObject(0).getJSONArray("ayahs").getJSONObject(0).getString("text")
        val showBism = i != 0 && i != 8

        val sb = StringBuilder()
        for (k in 0 until ayahs.length()) {
            val a = ayahs.getJSONObject(k)
            var t = a.getString("text")
            if (k == 0 && showBism && t.startsWith(bismillah)) t = t.removePrefix(bismillah).trim()
            sb.append(t).append(" ﴿").append(arabicDigits(a.getInt("numberInSurah").toString())).append("﴾ ")
        }

        val (sv, col) = page()
        col.addView(
            label("‹  رجوع للفهرس", 14f, GOLD, true).apply { setOnClickListener { openSurah = -1; show(2) } },
            lp(0, 8)
        )
        col.addView(label(s.getString("name"), 30f, GOLD, true).apply { gravity = Gravity.CENTER }, lp(0, 10))
        if (showBism) {
            col.addView(label(bismillah, 22f, CREAM).apply { gravity = Gravity.CENTER }, lp(0, 14))
        }
        col.addView(
            label(sb.toString().trim(), 26f, CREAM).apply {
                setLineSpacing(dp(14).toFloat(), 1f)
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.START
            }
        )
        return sv
    }
}

// -------------------------------------------------------------------- compass view

class CompassView(ctx: Context, private val gold: Int, private val card: Int, private val cream: Int) : View(ctx) {
    private var azimuth = 0f
    private var qibla = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(az: Float, q: Float) {
        azimuth = az
        qibla = q
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 10f

        p.style = Paint.Style.FILL
        p.color = card
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 8f
        p.color = gold
        c.drawCircle(cx, cy, r, p)

        // الشمال
        c.save()
        c.rotate(-azimuth, cx, cy)
        p.style = Paint.Style.FILL
        p.color = cream
        p.textAlign = Paint.Align.CENTER
        p.textSize = 40f
        p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("N", cx, cy - r + 52f, p)
        p.strokeWidth = 4f
        for (a in 0 until 360 step 30) {
            c.save()
            c.rotate(a.toFloat(), cx, cy)
            c.drawLine(cx, cy - r + 6f, cx, cy - r + 20f, p)
            c.restore()
        }
        c.restore()

        // سهم القبلة
        c.save()
        c.rotate(qibla - azimuth, cx, cy)
        p.style = Paint.Style.FILL
        p.color = gold
        val path = Path()
        path.moveTo(cx, cy - r * 0.72f)
        path.lineTo(cx - 20f, cy)
        path.lineTo(cx + 20f, cy)
        path.close()
        c.drawPath(path, p)
        // مربع صغير يرمز للكعبة
        p.color = Color.BLACK
        val k = RectF(cx - 22f, cy - r * 0.72f - 44f, cx + 22f, cy - r * 0.72f)
        c.drawRect(k, p)
        p.color = gold
        c.drawRect(cx - 22f, cy - r * 0.72f - 30f, cx + 22f, cy - r * 0.72f - 24f, p)
        c.restore()

        p.color = cream
        c.drawCircle(cx, cy, 10f, p)
    }
}
