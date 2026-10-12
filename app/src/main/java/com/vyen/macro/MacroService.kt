package com.vyen.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

class MacroService : AccessibilityService() {

    private class Step(val delay: Long, val duration: Long, val points: List<FloatArray>)

    private enum class Mode { IDLE, RECORDING, PLAYING }

    private val speeds = floatArrayOf(0.5f, 1f, 2f, 3f)
    private val loops = intArrayOf(1, 3, 5, 10, 0) // 0 = tanpa batas

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private val steps = ArrayList<Step>()
    private var mode = Mode.IDLE
    private var speedIdx = 1
    private var loopIdx = 0

    // panel
    private lateinit var panelRoot: LinearLayout
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var btnRec: TextView
    private lateinit var btnStop: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnSet: TextView
    private lateinit var settingsBox: LinearLayout
    private lateinit var btnSpeed: TextView
    private lateinit var btnLoop: TextView
    private lateinit var btnClear: TextView

    // rekam
    private var captureView: View? = null
    private var captureParams: WindowManager.LayoutParams? = null
    private var curPoints: MutableList<FloatArray>? = null
    private var downTime = 0L
    private var curDelay = 0L
    private var lastUp = 0L

    // putar
    private var token = 0
    private var stepIdx = 0
    private var loopsDone = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("macro", Context.MODE_PRIVATE)
        speedIdx = prefs.getInt("speed", 1).coerceIn(0, speeds.size - 1)
        loopIdx = prefs.getInt("loop", 0).coerceIn(0, loops.size - 1)
        loadSteps()
        buildPanel()
        refreshUi()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::wm.isInitialized) {
            stopAll()
            try { wm.removeView(panelRoot) } catch (e: Exception) {}
        }
    }

    // ---------- UI panel ----------

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun chip(label: String, color: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = 13f
        t.gravity = Gravity.CENTER
        t.setPadding(dp(12), dp(8), dp(12), dp(8))
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(10).toFloat()
        t.background = bg
        t.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        t.layoutParams = lp
        return t
    }

    private fun buildPanel() {
        panelRoot = LinearLayout(this)
        panelRoot.orientation = LinearLayout.VERTICAL
        panelRoot.setPadding(dp(6), dp(6), dp(6), dp(6))
        val bg = GradientDrawable()
        bg.setColor(0xE6202124.toInt())
        bg.cornerRadius = dp(14).toFloat()
        panelRoot.background = bg

        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.textSize = 12f
        status.setPadding(dp(8), dp(6), dp(8), dp(6))
        panelRoot.addView(status)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        btnRec = chip("● Rec", 0xFFD93025.toInt()) { startRecording() }
        btnStop = chip("■ Stop", 0xFF5F6368.toInt()) { stopAll() }
        btnPlay = chip("▶ Play", 0xFF188038.toInt()) { startPlayback() }
        btnSet = chip("⚙", 0xFF3C4043.toInt()) {
            settingsBox.visibility =
                if (settingsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        row.addView(btnRec)
        row.addView(btnStop)
        row.addView(btnPlay)
        row.addView(btnSet)
        panelRoot.addView(row)

        settingsBox = LinearLayout(this)
        settingsBox.orientation = LinearLayout.VERTICAL
        settingsBox.visibility = View.GONE
        btnSpeed = chip("", 0xFF3C4043.toInt()) {
            speedIdx = (speedIdx + 1) % speeds.size
            prefs.edit().putInt("speed", speedIdx).apply()
            refreshUi()
        }
        btnLoop = chip("", 0xFF3C4043.toInt()) {
            loopIdx = (loopIdx + 1) % loops.size
            prefs.edit().putInt("loop", loopIdx).apply()
            refreshUi()
        }
        btnClear = chip("Hapus rekaman", 0xFF8C1D18.toInt()) {
            if (mode == Mode.IDLE) {
                steps.clear()
                saveSteps()
                refreshUi()
            }
        }
        settingsBox.addView(btnSpeed)
        settingsBox.addView(btnLoop)
        settingsBox.addView(btnClear)
        panelRoot.addView(settingsBox)

        // geser panel dengan menyeret teks status
        status.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            var ox = 0
            var oy = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX; sy = e.rawY
                        ox = panelParams.x; oy = panelParams.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        panelParams.x = ox + (e.rawX - sx).toInt()
                        panelParams.y = oy + (e.rawY - sy).toInt()
                        try { wm.updateViewLayout(panelRoot, panelParams) } catch (ex: Exception) {}
                    }
                }
                return true
            }
        })

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        panelParams.gravity = Gravity.TOP or Gravity.START
        panelParams.x = dp(8)
        panelParams.y = dp(120)
        wm.addView(panelRoot, panelParams)
    }

    private fun loopLabel(): String = if (loops[loopIdx] == 0) "∞" else loops[loopIdx].toString()

    private fun refreshUi() {
        val playing = mode == Mode.PLAYING
        val idle = mode == Mode.IDLE
        val vis = if (playing) View.GONE else View.VISIBLE
        btnRec.visibility = vis
        btnPlay.visibility = vis
        btnSet.visibility = vis
        if (playing) settingsBox.visibility = View.GONE
        btnRec.alpha = if (idle) 1f else 0.4f
        btnPlay.alpha = if (idle) 1f else 0.4f
        btnStop.alpha = if (idle) 0.4f else 1f
        status.text = when (mode) {
            Mode.IDLE -> "Macro (Beta) · ${steps.size} langkah"
            Mode.RECORDING -> "● Merekam · ${steps.size} langkah"
            Mode.PLAYING -> "▶ Putaran ${loopsDone + 1}/${loopLabel()}"
        }
        btnSpeed.text = "Kecepatan: ${speeds[speedIdx]}x"
        btnLoop.text = "Ulangi: ${loopLabel()}x"
    }

    // ---------- Rekam ----------

    private fun startRecording() {
        if (mode != Mode.IDLE) return
        steps.clear()
        lastUp = 0L
        curPoints = null
        mode = Mode.RECORDING

        val v = View(this)
        v.setBackgroundColor(0x14FF0000) // tint merah tipis = sedang merekam
        v.setOnTouchListener { _, e ->
            onCaptureTouch(e)
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(v, lp)
        captureView = v
        captureParams = lp

        // taruh panel di atas layer rekam supaya tombol Stop tetap bisa ditekan
        try { wm.removeView(panelRoot) } catch (e: Exception) {}
        wm.addView(panelRoot, panelParams)
        refreshUi()
    }

    private fun onCaptureTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = e.eventTime
                curDelay = if (steps.isEmpty()) 0L else (e.eventTime - lastUp).coerceAtLeast(0L)
                curPoints = mutableListOf(floatArrayOf(e.rawX, e.rawY))
            }
            MotionEvent.ACTION_MOVE -> {
                val pts = curPoints ?: return
                if (e.pointerCount == 1) pts.add(floatArrayOf(e.rawX, e.rawY))
            }
            MotionEvent.ACTION_UP -> {
                val pts = curPoints ?: return
                pts.add(floatArrayOf(e.rawX, e.rawY))
                curPoints = null
                lastUp = e.eventTime
                val dur = (e.eventTime - downTime).coerceIn(1L, 59000L)
                val step = Step(curDelay, dur, simplify(pts))
                steps.add(step)
                refreshUi()
                passThrough(step)
            }
            MotionEvent.ACTION_CANCEL -> curPoints = null
        }
    }

    private fun simplify(p: List<FloatArray>): List<FloatArray> {
        // gerakan kecil = tap
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        var maxD = 0f
        for (q in p) {
            val dx = q[0] - p[0][0]
            val dy = q[1] - p[0][1]
            val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (d > maxD) maxD = d
        }
        if (maxD < slop) return listOf(p[0])
        if (p.size <= 60) return p
        val out = ArrayList<FloatArray>()
        val stepN = p.size / 60.0
        var i = 0.0
        while (i < p.size - 1) {
            out.add(p[i.toInt()])
            i += stepN
        }
        out.add(p[p.size - 1])
        return out
    }

    // Teruskan gerakan yang baru direkam ke aplikasi di bawah layer rekam.
    private fun passThrough(step: Step) {
        val v = captureView ?: return
        val lp = captureParams ?: return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(v, lp) } catch (e: Exception) {}
        val ok = dispatchGesture(buildGesture(step, 1f), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { restoreCapture() }
            override fun onCancelled(gestureDescription: GestureDescription?) { restoreCapture() }
        }, null)
        if (!ok) restoreCapture()
    }

    private fun restoreCapture() {
        val v = captureView ?: return
        val lp = captureParams ?: return
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try { wm.updateViewLayout(v, lp) } catch (e: Exception) {}
    }

    private fun removeCapture() {
        captureView?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        captureView = null
        captureParams = null
    }

    // ---------- Putar ----------

    private fun buildGesture(s: Step, speed: Float): GestureDescription {
        val path = Path()
        val pts = s.points
        path.moveTo(pts[0][0].coerceAtLeast(0f), pts[0][1].coerceAtLeast(0f))
        for (i in 1 until pts.size) {
            path.lineTo(pts[i][0].coerceAtLeast(0f), pts[i][1].coerceAtLeast(0f))
        }
        val dur = (s.duration / speed).toLong().coerceIn(1L, 59000L)
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, dur))
            .build()
    }

    private fun startPlayback() {
        if (mode != Mode.IDLE) return
        if (steps.isEmpty()) {
            status.text = "Belum ada rekaman"
            return
        }
        mode = Mode.PLAYING
        loopsDone = 0
        stepIdx = 0
        token++
        refreshUi()
        scheduleStep(token)
    }

    private fun scheduleStep(t: Int) {
        if (mode != Mode.PLAYING || t != token) return
        if (stepIdx >= steps.size) {
            loopsDone++
            val total = loops[loopIdx]
            if (total != 0 && loopsDone >= total) {
                stopAll()
                return
            }
            stepIdx = 0
        }
        if (stepIdx == 0) refreshUi()
        val s = steps[stepIdx]
        val speed = speeds[speedIdx]
        val wait = if (stepIdx == 0) {
            if (loopsDone == 0) 300L else 500L
        } else {
            (s.delay / speed).toLong()
        }
        handler.postDelayed({ dispatchStep(s, t) }, wait)
    }

    private fun dispatchStep(s: Step, t: Int) {
        if (mode != Mode.PLAYING || t != token) return
        val ok = dispatchGesture(buildGesture(s, speeds[speedIdx]), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                stepIdx++
                scheduleStep(t)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                stepIdx++
                scheduleStep(t)
            }
        }, null)
        if (!ok) stopAll()
    }

    private fun stopAll() {
        token++
        handler.removeCallbacksAndMessages(null)
        if (mode == Mode.RECORDING) {
            removeCapture()
            saveSteps()
        }
        mode = Mode.IDLE
        if (::status.isInitialized) refreshUi()
    }

    // ---------- Simpan / muat ----------

    private fun saveSteps() {
        val arr = JSONArray()
        for (s in steps) {
            val o = JSONObject()
            o.put("d", s.delay)
            o.put("t", s.duration)
            val pa = JSONArray()
            for (p in s.points) {
                pa.put(JSONArray().put(p[0].toDouble()).put(p[1].toDouble()))
            }
            o.put("p", pa)
            arr.put(o)
        }
        prefs.edit().putString("steps", arr.toString()).apply()
    }

    private fun loadSteps() {
        steps.clear()
        val raw = prefs.getString("steps", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pa = o.getJSONArray("p")
                val pts = ArrayList<FloatArray>()
                for (j in 0 until pa.length()) {
                    val q = pa.getJSONArray(j)
                    pts.add(floatArrayOf(q.getDouble(0).toFloat(), q.getDouble(1).toFloat()))
                }
                if (pts.isNotEmpty()) steps.add(Step(o.getLong("d"), o.getLong("t"), pts))
            }
        } catch (e: Exception) {
            steps.clear()
        }
    }
}
