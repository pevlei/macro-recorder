package com.vyen.macro

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Macro Recorder (Beta)"
        title.textSize = 22f
        root.addView(title)

        val info = TextView(this)
        info.textSize = 15f
        info.setPadding(0, pad / 2, 0, pad / 2)
        info.text = "Cara pakai:\n" +
            "1. Aktifkan layanan Macro Recorder di Pengaturan Aksesibilitas.\n" +
            "2. Panel melayang akan muncul di layar.\n" +
            "3. Tekan Rec, lalu lakukan tap atau geser seperti biasa.\n" +
            "4. Tekan Stop. Tekan Play untuk memutar ulang.\n" +
            "5. Tombol roda gigi untuk Settings (kecepatan, jumlah ulang, hapus rekaman).\n\n" +
            "Kalau tombol aksesibilitas abu-abu (Android 13+): buka Info aplikasi, " +
            "menu titik tiga, pilih Izinkan setelan terbatas."
        root.addView(info)

        status = TextView(this)
        status.textSize = 16f
        status.setPadding(0, 0, 0, pad / 2)
        root.addView(status)

        val b1 = Button(this)
        b1.text = "Buka Pengaturan Aksesibilitas"
        b1.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        root.addView(b1, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val b2 = Button(this)
        b2.text = "Info Aplikasi (izinkan setelan terbatas)"
        b2.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        root.addView(b2, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (serviceEnabled()) "Status: layanan AKTIF" else "Status: layanan belum aktif"
    }

    private fun serviceEnabled(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.contains("$packageName/")
    }
}
