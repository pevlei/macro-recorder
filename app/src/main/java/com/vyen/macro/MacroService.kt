package com.vyen.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject

class MacroService : AccessibilityService() {
    companion object { @Volatile var instance: MacroService? = null }

    private val h = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    private class Stroke(val start: Long, val dur: Long, val pts: List<Pair<Float, Float>>)

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { stop(); instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stop() }

    private fun parse(json: String): List<Stroke> {
        val s = json.trim()
        val a = if (s.startsWith("{")) JSONObject(s).getJSONArray("events") else JSONArray(s)
        val aktif = HashMap<Int, MutableList<Triple<Long, Float, Float>>>()
        val out = ArrayList<Stroke>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val p = o.optInt("p", 0)
            val t = o.getLong("t")
            val pt = Triple(t, o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
            when (o.getString("k")) {
                "d" -> aktif[p] = mutableListOf(pt)
                "m" -> aktif[p]?.add(pt)
                "u" -> aktif.remove(p)?.let { l ->
                    l.add(pt)
                    out.add(Stroke(l[0].first, (t - l[0].first).coerceIn(40L, 59000L), l.map { it.second to it.third }))
                }
            }
        }
        return out.sortedBy { it.start }
    }

    @Suppress("DEPRECATION")
    fun play(json: String, speed: Float, loops: Int, delayMs: Long): String? {
        val strokes = try { parse(json) } catch (e: Exception) { return "JSON tidak valid: ${e.message}" }
        if (strokes.isEmpty()) return "Tidak ada gerakan di JSON"
        stop()
        running = true
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels.toFloat()
        val hh = dm.heightPixels.toFloat()
        val total = strokes.maxOf { it.start + it.dur }
        var n = 0
        fun jalan() {
            if (!running) return
            for (s in strokes) {
                h.postDelayed({
                    if (!running) return@postDelayed
                    val path = Path()
                    var lx = s.pts[0].first
                    var ly = s.pts[0].second
                    path.moveTo(lx * w, ly * hh)
                    for (q in s.pts.drop(1)) {
                        if (q.first != lx || q.second != ly) {
                            path.lineTo(q.first * w, q.second * hh)
                            lx = q.first; ly = q.second
                        }
                    }
                    val g = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0L, maxOf(1L, (s.dur / speed).toLong())))
                        .build()
                    dispatchGesture(g, null, null)
                }, (s.start / speed).toLong())
            }
            h.postDelayed({ if (loops == 0 || ++n < loops) jalan() else stop() }, (total / speed).toLong() + 300L)
        }
        h.postDelayed({ jalan() }, delayMs)
        return null
    }

    fun stop() { running = false; h.removeCallbacksAndMessages(null) }
}
