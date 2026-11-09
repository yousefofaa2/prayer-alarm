package com.example.prayeralarm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.URL

class MainActivity : Activity() {

    private lateinit var status: TextView

    private val prayers = listOf(
        "Fajr" to "الفجر",
        "Dhuhr" to "الظهر",
        "Asr" to "العصر",
        "Maghrib" to "المغرب",
        "Isha" to "العشاء"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val button = Button(this).apply {
            text = "ضبط منبهات الصلاة في تطبيق الساعة"
            setOnClickListener { start() }
        }
        status = TextView(this).apply {
            textSize = 20f
            setPadding(0, 48, 0, 0)
        }
        root.addView(button)
        root.addView(status)
        setContentView(root)
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        } else {
            loadTimes()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        loadTimes() // لو رفض الإذن هيستخدم القاهرة كموقع افتراضي
    }

    private fun location(): Pair<Double, Double> {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.firstOrNull()
            (loc?.latitude ?: 30.0444) to (loc?.longitude ?: 31.2357)
        } catch (e: SecurityException) {
            30.0444 to 31.2357
        }
    }

    private fun loadTimes() {
        status.text = "جاري تحميل المواقيت..."
        val (lat, lng) = location()
        Thread {
            try {
                val url = "https://api.aladhan.com/v1/timings?latitude=$lat&longitude=$lng&method=5"
                val timings = JSONObject(URL(url).readText())
                    .getJSONObject("data").getJSONObject("timings")
                runOnUiThread { setAlarms(timings) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "تعذر تحميل المواقيت، تأكد من الإنترنت" }
            }
        }.start()
    }

    private fun setAlarms(timings: JSONObject) {
        val sb = StringBuilder("تم ضبط المنبهات:\n\n")
        for ((key, arabic) in prayers) {
            val time = timings.getString(key).take(5)
            val (h, m) = time.split(":").map { it.toInt() }
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, h)
                putExtra(AlarmClock.EXTRA_MINUTES, m)
                putExtra(AlarmClock.EXTRA_MESSAGE, "أذان $arabic")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            try {
                startActivity(intent)
                sb.append("$arabic  $time\n")
            } catch (e: Exception) {
                sb.append("$arabic: تعذر الضبط\n")
            }
        }
        status.text = sb.toString()
    }
}
