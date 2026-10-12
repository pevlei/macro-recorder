package com.vyen.macro

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        fun tv(t: String, sz: Float = 15f) = TextView(this).apply { text = t; textSize = sz }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }

        root.addView(tv("Macro Recorder", 24f))
        status = tv("")
        root.addView(status)
        root.addView(btn("1. Aktifkan layanan aksesibilitas") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        val json = EditText(this).apply {
            hint = "Tempel JSON hasil Ekspor dari dashboard"
            minLines = 5; gravity = Gravity.TOP; textSize = 12f
        }
        val spd = EditText(this).apply {
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val loop = EditText(this).apply { setText("1"); inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(json)
        root.addView(tv("Kecepatan (1 = normal)")); root.addView(spd)
        root.addView(tv("Ulang (0 = tanpa henti)")); root.addView(loop)
        root.addView(btn("2. Putar, mulai 5 detik lagi") {
            val svc = MacroService.instance
            if (svc == null) { toast("Layanan belum aktif. Tekan tombol 1 dulu."); return@btn }
            val sp = spd.text.toString().toFloatOrNull()?.coerceIn(0.1f, 10f) ?: 1f
            val lp = loop.text.toString().toIntOrNull() ?: 1
            val err = svc.play(json.text.toString(), sp, lp, 5000L)
            toast(err ?: "Mulai 5 detik lagi. Pindah ke app tujuan sekarang.")
        })
        root.addView(btn("Stop") { MacroService.instance?.stop(); toast("Dihentikan") })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (MacroService.instance != null) "Layanan aktif" else "Layanan belum aktif"
    }
}
