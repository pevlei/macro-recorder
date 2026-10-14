package com.vyen.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject

class MacroService : AccessibilityService() {

    // kind 0 = gesture, kind 1 = penanda level (points[0] = tombol -, points[1] = tombol +)
    private class Step(val kind: Int, val delay: Long, val duration: Long, val points: List<FloatArray>)

    private class Slot(val name: String) {
        val steps = ArrayList<Step>()
        var level = 4
    }

    private class Scan(val cur: Int, val max: Int, val toast: Boolean)

    private enum class Mode { IDLE, PICK, RECORDING, PLAYING }

    private class Ln(val text: String, val cx: Float, val cy: Float)

    private class Screen(val w: Int, val h: Int, val lines: List<Ln>) {
        fun find(word: String): Ln? = lines.firstOrNull { it.text.contains(word, true) }
        fun has(word: String): Boolean = find(word) != null
        fun exactAny(vararg words: String): Ln? =
            lines.firstOrNull { l -> words.any { l.text.equals(it, true) } }
    }

    private enum class Bf { WAIT, OPEN, TAB, LEVEL, SEARCH, POPUP, TROOPS, GO, CONFIRM }

    private val slots = listOf("Barbar Fort", "Food", "Wood", "Stone", "Gold", "Gems").map { Slot(it) }
    private val speeds = floatArrayOf(0.5f, 1f, 2f, 3f)

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var mode = Mode.IDLE
    private var note: String? = null
    private var speedIdx = 1
    private var autoSlot = -1 // -1 = mati
    private var paused = false

    // bot rally Barbarian Fort
    private var bfOn = false
    private var bf = Bf.WAIT
    private var bfLevel = 4
    private var bfSlot = 1
    private var bfTries = 0
    private var bfTabTries = 0
    private var bfTaps = 0
    private var bfFails = 0
    private var bfNull = 0
    private var bfCount = 0
    private var bfCtrBefore = 0
    private var bfLastEnd = 0L
    private var bfBusy = false
    private var bfStatus = ""
    private val bfSlotY = floatArrayOf(0.3984f, 0.4754f, 0.5525f, 0.6295f, 0.7066f)
    private val mapWords = listOf(
        "Kampanye", "Aliansi", "Komandan", "Surat", "Campaign", "Alliance", "Commander", "Mail"
    )
    private val bfTask = Runnable { bfTick() }

    // panel
    private lateinit var panelRoot: LinearLayout
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var btnRec: TextView
    private lateinit var btnStop: TextView
    private lateinit var btnPause: TextView
    private lateinit var btnSet: TextView
    private lateinit var btnLevel: TextView
    private lateinit var slotBox: LinearLayout
    private val slotChips = ArrayList<TextView>()
    private lateinit var settingsBox: LinearLayout
    private lateinit var btnAuto: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnClear: TextView
    private lateinit var bfRow: LinearLayout
    private lateinit var bfCfg: LinearLayout
    private lateinit var btnBf: TextView
    private lateinit var btnBfLv: TextView
    private lateinit var btnBfSlot: TextView
    private lateinit var btnBfStop: TextView
    private lateinit var dbgView: TextView
    private lateinit var row1: LinearLayout

    // rekam
    private var recSlot = 0
    private var captureView: View? = null
    private var captureParams: WindowManager.LayoutParams? = null
    private var curPoints: MutableList<FloatArray>? = null
    private var downTime = 0L
    private var curDelay = 0L
    private var lastUp = 0L
    private var levelPending = 0
    private var levelDelay = 0L
    private val levelPts = ArrayList<FloatArray>()

    // putar
    private val queue = ArrayList<Int>()
    private var runSlot = 0
    private var runSteps: List<Step> = emptyList()
    private var stepIdx = 0
    private var token = 0
    private var lastRunEnd = 0L

    // OCR
    private val ocrSupported = Build.VERSION.SDK_INT >= 30
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var scanning = false
    private var autoRuns = 0
    private val scanTask = Runnable { doScan() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("macro", Context.MODE_PRIVATE)
        speedIdx = prefs.getInt("speed", 1).coerceIn(0, speeds.size - 1)
        autoSlot = prefs.getInt("auto", -1).coerceIn(-1, slots.size - 1)
        bfLevel = prefs.getInt("bfLevel", 4).coerceIn(1, 5)
        bfSlot = prefs.getInt("bfSlot", 1).coerceIn(1, 5)
        loadAll()
        buildPanel()
        refreshUi()
        scheduleScan()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopAll(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::wm.isInitialized) {
            stopAll(false)
            try { wm.removeView(panelRoot) } catch (e: Exception) {}
        }
    }

    // ---------- Panel ----------

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
        status.maxLines = 2
        status.maxWidth = dp(320)
        panelRoot.addView(status)

        dbgView = TextView(this)
        dbgView.setTextColor(0xFFB0B0B0.toInt())
        dbgView.textSize = 9f
        dbgView.maxLines = 3
        dbgView.maxWidth = dp(320)
        dbgView.setPadding(dp(8), 0, dp(8), dp(4))
        dbgView.visibility = View.GONE
        panelRoot.addView(dbgView)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        btnRec = chip("● Rec", 0xFFD93025.toInt()) { onRecButton() }
        btnStop = chip("■ Stop", 0xFF5F6368.toInt()) { stopAll(true) }
        btnPause = chip("⏸ Jeda", 0xFF9A6B00.toInt()) { togglePause() }
        btnSet = chip("⚙", 0xFF3C4043.toInt()) {
            settingsBox.visibility =
                if (settingsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        row.addView(btnRec)
        row.addView(btnStop)
        row.addView(btnPause)
        row.addView(btnSet)
        panelRoot.addView(row)
        row1 = row

        btnLevel = chip("＋Level (tap − lalu +)", 0xFF1A73E8.toInt()) {
            if (mode == Mode.RECORDING && levelPending == 0) {
                levelPending = 2
                levelPts.clear()
                note = "Tap tombol − dulu"
                refreshUi()
            }
        }
        btnLevel.visibility = View.GONE
        panelRoot.addView(btnLevel)

        slotBox = LinearLayout(this)
        slotBox.orientation = LinearLayout.VERTICAL
        var rowL: LinearLayout? = null
        for (i in slots.indices) {
            if (i % 3 == 0) {
                rowL = LinearLayout(this)
                rowL.orientation = LinearLayout.HORIZONTAL
                slotBox.addView(rowL)
            }
            val c = chip("", 0xFF2D5BA8.toInt()) { onChip(i) }
            c.textSize = 12f
            c.setPadding(dp(8), dp(8), dp(8), dp(8))
            c.setOnLongClickListener {
                cycleLevel(i)
                true
            }
            slotChips.add(c)
            rowL!!.addView(c)
        }
        panelRoot.addView(slotBox)

        bfRow = LinearLayout(this)
        bfRow.orientation = LinearLayout.HORIZONTAL
        btnBf = chip("", 0xFF0B7A5A.toInt()) {
            if (mode == Mode.IDLE) bfSetOn(!bfOn) else say("Selesaikan makro dulu")
        }
        bfRow.addView(btnBf)
        btnBfStop = chip("■ Stop", 0xFF5F6368.toInt()) { stopAll(true) }
        btnBfStop.visibility = View.GONE
        bfRow.addView(btnBfStop)
        panelRoot.addView(bfRow)

        bfCfg = LinearLayout(this)
        bfCfg.orientation = LinearLayout.HORIZONTAL
        val bMinus = chip("−", 0xFF3C4043.toInt()) {
            bfLevel = (bfLevel - 1).coerceAtLeast(1)
            prefs.edit().putInt("bfLevel", bfLevel).apply()
            refreshUi()
        }
        btnBfLv = chip("", 0xFF3C4043.toInt()) {}
        val bPlus = chip("+", 0xFF3C4043.toInt()) {
            bfLevel = (bfLevel + 1).coerceAtMost(5)
            prefs.edit().putInt("bfLevel", bfLevel).apply()
            refreshUi()
        }
        btnBfSlot = chip("", 0xFF3C4043.toInt()) {
            bfSlot = if (bfSlot >= 5) 1 else bfSlot + 1
            prefs.edit().putInt("bfSlot", bfSlot).apply()
            refreshUi()
        }
        bfCfg.addView(bMinus)
        bfCfg.addView(btnBfLv)
        bfCfg.addView(bPlus)
        bfCfg.addView(btnBfSlot)
        panelRoot.addView(bfCfg)

        settingsBox = LinearLayout(this)
        settingsBox.orientation = LinearLayout.VERTICAL
        settingsBox.visibility = View.GONE
        btnAuto = chip("", 0xFF3C4043.toInt()) { cycleAuto() }
        btnSpeed = chip("", 0xFF3C4043.toInt()) {
            speedIdx = (speedIdx + 1) % speeds.size
            prefs.edit().putInt("speed", speedIdx).apply()
            refreshUi()
        }
        btnClear = chip("Hapus semua rekaman", 0xFF8C1D18.toInt()) {
            if (mode == Mode.IDLE) {
                for (s in slots) s.steps.clear()
                saveAll()
                refreshUi()
            }
        }
        settingsBox.addView(btnAuto)
        settingsBox.addView(btnSpeed)
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

    private fun refreshUi() {
        val idle = mode == Mode.IDLE
        val playing = mode == Mode.PLAYING
        val rec = mode == Mode.RECORDING
        val bot = bfOn
        btnRec.visibility = if (playing || rec || bot) View.GONE else View.VISIBLE
        btnSet.visibility = if (playing || rec || bot) View.GONE else View.VISIBLE
        btnPause.visibility = if (rec) View.GONE else View.VISIBLE
        slotBox.visibility = if (playing || rec || bot) View.GONE else View.VISIBLE
        bfRow.visibility = if (playing || rec) View.GONE else View.VISIBLE
        bfCfg.visibility = if (playing || rec || bot) View.GONE else View.VISIBLE
        btnLevel.visibility = if (rec) View.VISIBLE else View.GONE
        row1.visibility = if (bot) View.GONE else View.VISIBLE
        btnBfStop.visibility = if (bot) View.VISIBLE else View.GONE
        dbgView.visibility = if (bot) View.VISIBLE else View.GONE
        if (playing || rec || bot) settingsBox.visibility = View.GONE
        btnRec.text = if (mode == Mode.PICK) "✕ Batal" else "● Rec"
        btnStop.alpha = if (idle && !paused && !bot) 0.4f else 1f
        btnPause.text = if (paused) "▶ Lanjut" else "⏸ Jeda"
        btnBf.text = (if (bfOn) "☑" else "☐") + " Rally BF"
        btnBfLv.text = "Lv $bfLevel"
        btnBfSlot.text = "Slot $bfSlot"
        for (i in slots.indices) {
            val s = slots[i]
            slotChips[i].text = "${s.name} L${s.level}"
            slotChips[i].alpha = if (s.steps.isEmpty() && mode != Mode.PICK) 0.45f else 1f
        }
        btnAuto.text = "Auto: " + (if (autoSlot < 0) "Mati" else slots[autoSlot].name)
        btnSpeed.text = "Kecepatan: ${speeds[speedIdx]}x"
        status.text = note ?: if (bfOn) "BF · ${bfCount}x · $bfStatus" else when (mode) {
            Mode.IDLE -> if (paused) "Macro (Beta) · dijeda" else "Macro (Beta) · siap"
            Mode.PICK -> "Pilih slot untuk direkam"
            Mode.RECORDING -> "● ${slots[recSlot].name} · ${slots[recSlot].steps.size} langkah"
            Mode.PLAYING ->
                "▶ ${slots[runSlot].name} · ${(stepIdx + 1).coerceAtMost(runSteps.size)}/${runSteps.size}"
        }
    }

    private fun say(s: String) {
        note = s
        refreshUi()
    }

    // ---------- Tombol ----------

    private fun onRecButton() {
        if (bfOn) {
            say("Matikan Rally BF dulu")
            return
        }
        note = null
        when (mode) {
            Mode.IDLE -> mode = Mode.PICK
            Mode.PICK -> mode = Mode.IDLE
            else -> return
        }
        refreshUi()
    }

    private fun onChip(i: Int) {
        if (bfOn) {
            say("Matikan Rally BF dulu")
            return
        }
        note = null
        when (mode) {
            Mode.PICK -> startRecording(i)
            Mode.IDLE -> enqueue(i)
            else -> {}
        }
    }

    private fun cycleLevel(i: Int) {
        if (mode != Mode.IDLE) return
        val s = slots[i]
        s.level = if (s.level >= 6) 1 else s.level + 1
        saveAll()
        refreshUi()
    }

    private fun cycleAuto() {
        autoSlot = if (autoSlot >= slots.size - 1) -1 else autoSlot + 1
        prefs.edit().putInt("auto", autoSlot).apply()
        paused = false
        autoRuns = 0
        note = if (autoSlot >= 0 && !ocrSupported) "Auto butuh Android 11 ke atas" else null
        refreshUi()
        scheduleScan()
    }

    private fun togglePause() {
        paused = !paused
        if (!paused) autoRuns = 0
        note = null
        refreshUi()
        scheduleScan()
    }

    // ---------- Rekam ----------

    private fun startRecording(i: Int) {
        if (mode != Mode.PICK && mode != Mode.IDLE) return
        recSlot = i
        slots[i].steps.clear()
        lastUp = 0L
        curPoints = null
        levelPending = 0
        levelPts.clear()
        note = null
        mode = Mode.RECORDING

        val v = View(this)
        v.setBackgroundColor(0x14FF0000)
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

        try { wm.removeView(panelRoot) } catch (e: Exception) {}
        wm.addView(panelRoot, panelParams)
        refreshUi()
    }

    private fun onCaptureTouch(e: MotionEvent) {
        val st = slots[recSlot].steps
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = e.eventTime
                curDelay = if (st.isEmpty()) 0L else (e.eventTime - lastUp).coerceAtLeast(0L)
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
                val simple = simplify(pts)
                if (levelPending > 0) {
                    if (levelPending == 2) levelDelay = curDelay
                    levelPts.add(floatArrayOf(simple[0][0], simple[0][1]))
                    levelPending--
                    if (levelPending == 0) {
                        st.add(Step(1, levelDelay, 1L, ArrayList(levelPts)))
                        levelPts.clear()
                        note = null
                    } else {
                        note = "Sekarang tap tombol +"
                    }
                    refreshUi()
                    passThrough(Step(0, 0L, dur, simple))
                } else {
                    val step = Step(0, curDelay, dur, simple)
                    st.add(step)
                    refreshUi()
                    passThrough(step)
                }
            }
            MotionEvent.ACTION_CANCEL -> curPoints = null
        }
    }

    private fun simplify(p: List<FloatArray>): List<FloatArray> {
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

    // ---------- Putar + antrean ----------

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

    // ubah penanda level jadi tap: turun ke level 1 dulu, lalu naik sesuai level slot
    private fun expand(s: Slot): List<Step> {
        val out = ArrayList<Step>()
        for (st in s.steps) {
            if (st.kind == 0) {
                out.add(st)
            } else {
                val minus = st.points[0]
                val plus = st.points[1]
                for (n in 0 until 6) {
                    out.add(Step(0, if (n == 0) st.delay else 250L, 60L, listOf(minus)))
                }
                for (n in 0 until (s.level - 1)) {
                    out.add(Step(0, 250L, 60L, listOf(plus)))
                }
            }
        }
        return out
    }

    private fun enqueue(i: Int) {
        if (bfOn) return
        if (slots[i].steps.isEmpty()) {
            say("${slots[i].name} masih kosong, rekam dulu")
            return
        }
        if (queue.contains(i) || (mode == Mode.PLAYING && runSlot == i)) return
        queue.add(i)
        runNext()
    }

    private fun runNext() {
        if (mode != Mode.IDLE || queue.isEmpty()) return
        startSlot(queue.removeAt(0))
    }

    private fun startSlot(i: Int) {
        runSlot = i
        runSteps = expand(slots[i])
        stepIdx = 0
        note = null
        mode = Mode.PLAYING
        token++
        refreshUi()
        scheduleStep(token)
    }

    private fun scheduleStep(t: Int) {
        if (mode != Mode.PLAYING || t != token) return
        if (stepIdx >= runSteps.size) {
            finishRun()
            return
        }
        val s = runSteps[stepIdx]
        val wait = if (stepIdx == 0) 300L else (s.delay / speeds[speedIdx]).toLong()
        handler.postDelayed({ dispatchStep(s, t) }, wait)
    }

    private fun dispatchStep(s: Step, t: Int) {
        if (mode != Mode.PLAYING || t != token) return
        refreshUi()
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
        if (!ok) {
            stopAll(false)
            say("Gesture gagal dijalankan")
        }
    }

    private fun finishRun() {
        mode = Mode.IDLE
        lastRunEnd = System.currentTimeMillis()
        note = null
        refreshUi()
        runNext()
        scheduleScan()
    }

    private fun stopAll(userStop: Boolean) {
        if (userStop) bfSetOn(false)
        token++
        handler.removeCallbacksAndMessages(null)
        if (::panelRoot.isInitialized) panelRoot.alpha = 1f
        val wasRunning = mode == Mode.PLAYING || queue.isNotEmpty()
        if (mode == Mode.RECORDING) {
            removeCapture()
            saveAll()
        }
        queue.clear()
        mode = Mode.IDLE
        note = null
        if (userStop && wasRunning) paused = true
        if (::status.isInitialized) {
            refreshUi()
            scheduleScan()
            if (bfOn) bfSchedule(1000)
        }
    }

    // ---------- OCR: pantau counter pasukan + teks "pasukan kembali" ----------

    private fun scheduleScan() {
        handler.removeCallbacks(scanTask)
        if (!ocrSupported || autoSlot < 0) return
        handler.postDelayed(scanTask, 3000L)
    }

    private fun grab(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            cb(null)
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    hb.close()
                    cb(bmp)
                }
                override fun onFailure(errorCode: Int) {
                    cb(null)
                }
            })
    }

    private fun doScan() {
        if (autoSlot < 0 || bfOn || mode != Mode.IDLE || paused || scanning || queue.isNotEmpty()) {
            scheduleScan()
            return
        }
        scanning = true
        grab { bmp ->
            if (bmp == null) {
                scanning = false
                scheduleScan()
                return@grab
            }
            val w = bmp.width
            val h = bmp.height
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { t ->
                    bmp.recycle()
                    scanning = false
                    onScan(analyze(t, w, h))
                }
                .addOnFailureListener {
                    bmp.recycle()
                    scanning = false
                    scheduleScan()
                }
        }
    }

    private fun analyze(t: Text, w: Int, h: Int): Scan {
        var cur = -1
        var max = -1
        var toast = false
        val re = Regex("(\\d{1,2})\\s*/\\s*(\\d{1,2})")
        for (b in t.textBlocks) {
            for (l in b.lines) {
                val s = l.text.trim()
                if (s.contains("kembali ke kota", true)) toast = true
                val box = l.boundingBox
                if (box != null && box.centerX() > w * 0.8 && box.centerY() < h * 0.4) {
                    val m = re.find(s)
                    if (m != null) {
                        cur = m.groupValues[1].toInt()
                        max = m.groupValues[2].toInt()
                    }
                }
            }
        }
        return Scan(cur, max, toast)
    }

    private fun onScan(sc: Scan) {
        if (mode == Mode.IDLE && !paused && autoSlot >= 0) {
            if (sc.cur >= 0 && sc.max > 0 && sc.cur >= sc.max) autoRuns = 0
            val freeSlot = sc.cur >= 0 && sc.max > 0 && sc.cur < sc.max
            val cooled = System.currentTimeMillis() - lastRunEnd > 10000L
            if ((freeSlot || sc.toast) && cooled) {
                if (autoRuns >= 3) {
                    paused = true
                    say("Auto berhenti: counter tidak naik 3x")
                } else {
                    autoRuns++
                    enqueue(autoSlot)
                }
            }
        }
        scheduleScan()
    }

    // ---------- Bot Rally Barbarian Fort ----------

    private fun bfSetOn(on: Boolean) {
        if (on == bfOn) return
        if (on && !ocrSupported) {
            note = "Bot BF butuh Android 11 ke atas"
            refreshUi()
            return
        }
        bfOn = on
        if (on) {
            bf = Bf.WAIT
            bfBusy = false
            bfFails = 0
            bfNull = 0
            bfCount = 0
            bfLastEnd = 0L
            bfStatus = "mulai"
            note = null
            refreshUi()
            // geser panel ke tepi atas supaya tidak menutupi titik tap bot
            panelParams.x = (resources.displayMetrics.widthPixels * 0.36f).toInt()
            panelParams.y = dp(2)
            try { wm.updateViewLayout(panelRoot, panelParams) } catch (e: Exception) {}
            bfSchedule(500)
        } else {
            handler.removeCallbacks(bfTask)
            panelRoot.alpha = 1f
            bfStatus = ""
            refreshUi()
        }
    }

    private fun bfSchedule(ms: Long) {
        handler.removeCallbacks(bfTask)
        if (bfOn) handler.postDelayed(bfTask, ms)
    }

    // panel disembunyikan sebentar supaya OCR tidak membaca panel sendiri / tertutup panel
    private fun readScreen(cb: (Screen?) -> Unit) {
        panelRoot.alpha = 0f
        handler.postDelayed({ readScreenNow(cb) }, 150L)
    }

    private fun readScreenNow(cb: (Screen?) -> Unit) {
        grab { bmp ->
            panelRoot.alpha = 1f
            if (bmp == null) {
                cb(null)
                return@grab
            }
            val w = bmp.width
            val h = bmp.height
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { t ->
                    bmp.recycle()
                    val ls = ArrayList<Ln>()
                    for (b in t.textBlocks) {
                        for (l in b.lines) {
                            val bx = l.boundingBox
                            if (bx != null) ls.add(Ln(l.text.trim(), bx.exactCenterX(), bx.exactCenterY()))
                        }
                    }
                    val scr = Screen(w, h, ls)
                    showDbg(scr)
                    cb(scr)
                }
                .addOnFailureListener {
                    bmp.recycle()
                    cb(null)
                }
        }
    }

    private fun showDbg(sc: Screen) {
        val t = sc.lines.take(12).joinToString(" | ") { it.text.take(16) }
        dbgView.text = "OCR: " + t.take(150)
    }

    private fun tapPx(x: Float, y: Float, done: () -> Unit) {
        val p = Path()
        p.moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f))
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 60L))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done() }
        }, null)
        if (!ok) done()
    }

    private fun tapFrac(sc: Screen, fx: Float, fy: Float, done: () -> Unit) {
        tapPx(sc.w * fx, sc.h * fy, done)
    }

    private fun tapSeq(pts: List<FloatArray>, i: Int, done: () -> Unit) {
        if (!bfOn) return
        if (i >= pts.size) {
            done()
            return
        }
        tapPx(pts[i][0], pts[i][1]) {
            handler.postDelayed({ tapSeq(pts, i + 1, done) }, 350L)
        }
    }

    // peta terdeteksi dari menu bawah (kalau terbuka) ATAU dari teks koordinat X:... Y:... di kiri atas
    private fun mapVisible(sc: Screen): Boolean {
        if (mapWords.count { sc.has(it) } >= 2) return true
        val rx = Regex("(?i)\\bx\\s*:\\s*\\d{2,4}")
        val ry = Regex("(?i)\\by\\s*:\\s*\\d{2,4}")
        var fx = false
        var fy = false
        for (l in sc.lines) {
            if (l.cy < sc.h * 0.15f && l.cx < sc.w * 0.6f) {
                if (rx.containsMatchIn(l.text)) fx = true
                if (ry.containsMatchIn(l.text)) fy = true
            }
        }
        return fx && fy
    }

    private fun counterOf(sc: Screen): Pair<Int, Int>? {
        val re = Regex("(\\d{1,2})\\s*/\\s*(\\d{1,2})")
        for (l in sc.lines) {
            if (l.cx > sc.w * 0.8f && l.cy < sc.h * 0.4f) {
                val m = re.find(l.text)
                if (m != null) return Pair(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            }
        }
        return null
    }

    private fun readLevel(sc: Screen): Int? {
        val re = Regex("(?i)level\\D{0,3}(\\d{1,2})")
        for (l in sc.lines) {
            val m = re.find(l.text)
            if (m != null) return m.groupValues[1].toInt()
        }
        return null
    }

    private fun bfTick() {
        if (!bfOn) return
        if (mode != Mode.IDLE || paused || bfBusy) {
            bfSchedule(1000)
            return
        }
        bfBusy = true
        readScreen { sc ->
            bfBusy = false
            if (!bfOn) return@readScreen
            if (sc == null) {
                bfNull++
                if (bfNull >= 5) {
                    bfNull = 0
                    bfFail("Screenshot gagal, game mungkin memblokir", null)
                } else {
                    bfSchedule(1000)
                }
                return@readScreen
            }
            bfNull = 0
            bfAct(sc)
        }
    }

    private fun bfBlindLevel(sc: Screen) {
        val pts = ArrayList<FloatArray>()
        for (n in 0 until 6) pts.add(floatArrayOf(sc.w * 0.1586f, sc.h * 0.5377f))
        for (n in 0 until (bfLevel - 1)) pts.add(floatArrayOf(sc.w * 0.3783f, sc.h * 0.5377f))
        tapSeq(pts, 0) {
            bf = Bf.SEARCH
            bfTries = 0
            bfSchedule(500)
        }
    }

    private fun bfFail(msg: String, sc: Screen?) {
        bfFails++
        if (sc != null && !mapVisible(sc) &&
            (sc.has("BERGERAK") || sc.exactAny("CARI", "SEARCH") != null || sc.exactAny("RALLY") != null)
        ) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        if (bfFails >= 3) {
            bfSetOn(false)
            note = "BF berhenti: $msg"
            refreshUi()
            return
        }
        bf = Bf.WAIT
        bfStatus = "gagal ($bfFails/3): $msg"
        refreshUi()
        bfSchedule(5000)
    }

    private fun bfAct(sc: Screen) {
        when (bf) {
            Bf.WAIT -> {
                val ctr = counterOf(sc)
                val onMap = mapVisible(sc)
                val free = ctr == null || ctr.first < ctr.second
                val cooled = System.currentTimeMillis() - bfLastEnd > 10000L
                if (onMap && free && cooled) {
                    bfCtrBefore = ctr?.first ?: 0
                    bfTries = 0
                    bfTabTries = 0
                    bfTaps = 0
                    bf = Bf.OPEN
                    bfStatus = "membuka pencarian"
                    refreshUi()
                    bfSchedule(100)
                } else {
                    bfStatus = if (!onMap) "menunggu peta" else "menunggu pasukan pulang"
                    refreshUi()
                    bfSchedule(3000)
                }
            }
            Bf.OPEN -> {
                if (sc.exactAny("CARI", "SEARCH") != null) {
                    bf = Bf.TAB
                    bfStatus = "pilih tab fort"
                    refreshUi()
                    bfSchedule(100)
                    return
                }
                if (bfTries++ >= 4) {
                    bfFail("Menu pencarian tidak terbuka", sc)
                    return
                }
                tapFrac(sc, 0.0649f, 0.7508f) { bfSchedule(1300) }
            }
            Bf.TAB -> {
                val ln = sc.exactAny("Barbarian Fort")
                bf = Bf.LEVEL
                bfTries = 0
                bfTaps = 0
                bfStatus = "atur level $bfLevel"
                refreshUi()
                if (ln != null) {
                    tapPx(ln.cx, ln.cy) { bfSchedule(900) }
                } else {
                    tapFrac(sc, 0.3304f, 0.3279f) { bfSchedule(900) }
                }
            }
            Bf.LEVEL -> {
                if (!sc.has("benteng") && bfTabTries < 3) {
                    bfTabTries++
                    val ln = sc.exactAny("Barbarian Fort")
                    if (ln != null) {
                        tapPx(ln.cx, ln.cy) { bfSchedule(900) }
                    } else {
                        tapFrac(sc, 0.3304f, 0.3279f) { bfSchedule(900) }
                    }
                    return
                }
                val lv = readLevel(sc)
                if (lv == null) {
                    bfTries++
                    if (bfTries > 3) bfBlindLevel(sc) else bfSchedule(700)
                    return
                }
                if (lv == bfLevel) {
                    bf = Bf.SEARCH
                    bfTries = 0
                    bfStatus = "tekan cari"
                    refreshUi()
                    bfSchedule(300)
                    return
                }
                if (bfTaps++ >= 12) {
                    bfFail("Level tidak bisa diatur", sc)
                    return
                }
                val fx = if (lv < bfLevel) 0.3783f else 0.1586f
                tapFrac(sc, fx, 0.5377f) { bfSchedule(500) }
            }
            Bf.SEARCH -> {
                val ln = sc.exactAny("CARI", "SEARCH")
                if (ln == null) {
                    if (bfTries++ >= 3) {
                        bf = Bf.OPEN
                        bfTries = 0
                    }
                    bfSchedule(700)
                    return
                }
                bf = Bf.POPUP
                bfTries = 0
                bfStatus = "menunggu fort"
                refreshUi()
                tapPx(ln.cx, ln.cy) { bfSchedule(1500) }
            }
            Bf.POPUP -> {
                val ln = sc.exactAny("RALLY")
                if (ln == null) {
                    if (bfTries++ >= 8) {
                        bfFail("Fort tidak ketemu atau tombol RALLY tidak muncul", sc)
                    } else {
                        bfSchedule(1000)
                    }
                    return
                }
                bf = Bf.TROOPS
                bfTries = 0
                bfStatus = "pilih pasukan"
                refreshUi()
                tapPx(ln.cx, ln.cy) { bfSchedule(1500) }
            }
            Bf.TROOPS -> {
                // layar pilih pasukan sudah terbuka -> pilih slot
                if (sc.has("BERGERAK") || sc.has("March")) {
                    bf = Bf.GO
                    bfTries = 0
                    tapFrac(sc, 0.7899f, bfSlotY[bfSlot - 1]) { bfSchedule(900) }
                    return
                }
                // setelah RALLY: tap tombol "Pasukan Baru" di panel antrean kanan atas
                val nb = sc.exactAny("Pasukan Baru", "Pasukan", "New Troops")
                    ?: sc.lines.firstOrNull {
                        it.cx > sc.w * 0.7f && it.cy < sc.h * 0.3f &&
                            it.text.contains("Pasukan", true) && !it.text.contains("Bentuk", true)
                    }
                if (nb != null) {
                    tapPx(nb.cx, nb.cy) { bfSchedule(1500) }
                    return
                }
                if (sc.has("dari kotamu")) {
                    tapFrac(sc, 0.8024f, 0.2f) { bfSchedule(1500) }
                    return
                }
                if (sc.has("Antrean") && bfTries in 2..5) {
                    bfTries++
                    tapFrac(sc, 0.9306f, 0.1475f) { bfSchedule(1500) }
                    return
                }
                if (bfTries++ >= 8) {
                    bfFail("Layar pasukan tidak muncul", sc)
                } else {
                    bfSchedule(1000)
                }
            }
            Bf.GO -> {
                val ln = sc.find("BERGERAK")
                if (ln == null) {
                    if (bfTries++ >= 4) {
                        bfFail("Tombol kirim pasukan hilang", sc)
                    } else {
                        bfSchedule(800)
                    }
                    return
                }
                bf = Bf.CONFIRM
                bfTries = 0
                bfStatus = "kirim pasukan"
                refreshUi()
                tapPx(ln.cx, ln.cy) { bfSchedule(1500) }
            }
            Bf.CONFIRM -> {
                val banner = sc.has("Serangan Rally") || sc.has("Melancarkan")
                val now = counterOf(sc)?.first ?: 0
                if (banner || (mapVisible(sc) && now > bfCtrBefore)) {
                    bfCount++
                    bfFails = 0
                    bfLastEnd = System.currentTimeMillis()
                    bf = Bf.WAIT
                    bfStatus = "rally terkirim"
                    refreshUi()
                    bfSchedule(3000)
                    return
                }
                if (bfTries++ >= 7) {
                    bfFail("Rally tidak terkonfirmasi", sc)
                } else {
                    bfSchedule(1000)
                }
            }
        }
    }

    // ---------- Simpan / muat ----------

    private fun saveAll() {
        val arr = JSONArray()
        for (s in slots) {
            val o = JSONObject()
            o.put("name", s.name)
            o.put("level", s.level)
            val sa = JSONArray()
            for (st in s.steps) {
                val so = JSONObject()
                so.put("k", st.kind)
                so.put("d", st.delay)
                so.put("t", st.duration)
                val pa = JSONArray()
                for (p in st.points) {
                    pa.put(JSONArray().put(p[0].toDouble()).put(p[1].toDouble()))
                }
                so.put("p", pa)
                sa.put(so)
            }
            o.put("steps", sa)
            arr.put(o)
        }
        prefs.edit().putString("slots", arr.toString()).apply()
    }

    private fun loadAll() {
        val raw = prefs.getString("slots", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val slot = slots.firstOrNull { it.name == o.getString("name") } ?: continue
                slot.level = o.optInt("level", 4).coerceIn(1, 6)
                slot.steps.clear()
                val sa = o.getJSONArray("steps")
                for (j in 0 until sa.length()) {
                    val so = sa.getJSONObject(j)
                    val pa = so.getJSONArray("p")
                    val pts = ArrayList<FloatArray>()
                    for (k in 0 until pa.length()) {
                        val q = pa.getJSONArray(k)
                        pts.add(floatArrayOf(q.getDouble(0).toFloat(), q.getDouble(1).toFloat()))
                    }
                    if (pts.isNotEmpty()) {
                        slot.steps.add(Step(so.getInt("k"), so.getLong("d"), so.getLong("t"), pts))
                    }
                }
            }
        } catch (e: Exception) {
            for (s in slots) s.steps.clear()
        }
    }
}
