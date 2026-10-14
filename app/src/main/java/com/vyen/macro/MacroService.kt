package com.vyen.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MacroService : AccessibilityService() {

    private class Ln(val text: String, val cx: Float, val cy: Float)

    private class Screen(val w: Int, val h: Int, val lines: List<Ln>) {
        fun find(word: String): Ln? = lines.firstOrNull { it.text.contains(word, true) }
        fun has(word: String): Boolean = find(word) != null
        fun exactAny(vararg words: String): Ln? =
            lines.firstOrNull { l -> words.any { l.text.equals(it, true) } }
    }

    private enum class Bf { WAIT, OPEN, TAB, LEVEL, SEARCH, POPUP, PREP, TROOPS, GO, CONFIRM }

    private val speeds = floatArrayOf(0.5f, 1f, 2f, 3f)

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var note: String? = null
    private var speedIdx = 1

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
    private var savedX = 0
    private var savedY = 0
    private val bfSlotY = floatArrayOf(0.3984f, 0.4754f, 0.5525f, 0.6295f, 0.7066f)
    private val mapWords = listOf(
        "Kampanye", "Aliansi", "Komandan", "Surat", "Campaign", "Alliance", "Commander", "Mail"
    )
    private val bfTask = Runnable { bfTick() }

    // bot Farm Gems: geser peta (spiral) sambil cari gambar gem deposit
    private class GemHit(val cx: Float, val cy: Float, val count: Int, val bw: Float, val bh: Float)

    private enum class Gm { CHECK, SCAN }

    private var gemOn = false
    private var gm = Gm.CHECK
    private var gemBusy = false
    private var gemSteps = 0
    private var gemNull = 0
    private var gemWait = 0
    private var gemDir = 0
    private var gemLegLen = 1
    private var gemLegDone = 0
    private var gemLegsAtLen = 0
    private var gemStatus = ""
    private val gemMaxSteps = 60
    private val gemTask = Runnable { gemTick() }
    private val gemExec = Executors.newSingleThreadExecutor()
    private var gemMarker: View? = null
    private var gemW = 0
    private var gemH = 0

    // panel
    private lateinit var panelRoot: LinearLayout
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var dbgView: TextView
    private lateinit var toggle: TextView
    private lateinit var gemToggle: TextView
    private lateinit var configBox: LinearLayout
    private lateinit var settingsBox: LinearLayout
    private lateinit var levelText: TextView
    private val slotBtns = ArrayList<TextView>()
    private val speedBtns = ArrayList<TextView>()

    // OCR
    private val ocrSupported = Build.VERSION.SDK_INT >= 30
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("macro", Context.MODE_PRIVATE)
        if (::panelRoot.isInitialized) {
            try { wm.removeView(panelRoot) } catch (e: Exception) {}
            slotBtns.clear()
            speedBtns.clear()
        }
        speedIdx = prefs.getInt("speed", 1).coerceIn(0, speeds.size - 1)
        bfLevel = prefs.getInt("bfLevel", 4).coerceIn(1, 5)
        bfSlot = prefs.getInt("bfSlot", 1).coerceIn(1, 5)
        buildPanel()
        refreshUi()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        if (bfOn) bfSetOn(false)
        if (gemOn) gemSetOn(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::wm.isInitialized) {
            handler.removeCallbacksAndMessages(null)
            bfOn = false
            gemOn = false
            gemExec.shutdown()
            gemHideMarker()
            try { wm.removeView(panelRoot) } catch (e: Exception) {}
        }
    }

    // ---------- Panel ----------

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun pill(label: String, sp: Float, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = sp
        t.gravity = Gravity.CENTER
        t.setTypeface(t.typeface, Typeface.BOLD)
        t.background = roundBg(0xFF2B2F36.toInt(), 12)
        t.setPadding(dp(14), dp(10), dp(14), dp(10))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun label(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(0xFFB8BEC8.toInt())
        t.textSize = 13f
        return t
    }

    private fun margin(m: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(dp(m), dp(m), dp(m), dp(m))
        return lp
    }

    private fun row(topDp: Int): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(topDp) }
        return r
    }

    private fun speedLabel(i: Int): String {
        val v = speeds[i]
        return if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x"
    }

    private fun buildPanel() {
        panelRoot = LinearLayout(this)
        panelRoot.orientation = LinearLayout.VERTICAL
        panelRoot.setPadding(dp(14), dp(12), dp(14), dp(12))
        val bg = roundBg(0xF2161A20.toInt(), 22)
        bg.setStroke(dp(1), 0x33FFFFFF)
        panelRoot.background = bg

        // header: judul + status (seret untuk memindah) dan tombol pengaturan
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val titleBox = LinearLayout(this)
        titleBox.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = "Rally Bot"
        title.setTextColor(Color.WHITE)
        title.textSize = 17f
        title.setTypeface(title.typeface, Typeface.BOLD)
        status = TextView(this)
        status.setTextColor(0xFF9AA3AF.toInt())
        status.textSize = 12f
        status.maxLines = 2
        titleBox.addView(title)
        titleBox.addView(status)
        header.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val btnSettings = pill("⚙", 17f) {
            if (!bfOn) {
                settingsBox.visibility =
                    if (settingsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        header.addView(btnSettings)
        panelRoot.addView(header)

        // saklar utama
        val swRow = row(12)
        val swLabel = TextView(this)
        swLabel.text = "Rally Barbarian Fort"
        swLabel.setTextColor(Color.WHITE)
        swLabel.textSize = 15f
        swRow.addView(swLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        toggle = pill("OFF", 14f) { bfSetOn(!bfOn) }
        toggle.minWidth = dp(64)
        swRow.addView(toggle)
        panelRoot.addView(swRow)

        val gemRow = row(8)
        val gemLabel = TextView(this)
        gemLabel.text = "Farm Gems"
        gemLabel.setTextColor(Color.WHITE)
        gemLabel.textSize = 15f
        gemRow.addView(gemLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        gemToggle = pill("OFF", 14f) { gemSetOn(!gemOn) }
        gemToggle.minWidth = dp(64)
        gemRow.addView(gemToggle)
        panelRoot.addView(gemRow)

        // pengaturan bot (disembunyikan saat bot jalan)
        configBox = LinearLayout(this)
        configBox.orientation = LinearLayout.VERTICAL

        val lvRow = row(10)
        lvRow.addView(label("Level fort"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val minus = pill("−", 18f) {
            bfLevel = (bfLevel - 1).coerceAtLeast(1)
            prefs.edit().putInt("bfLevel", bfLevel).apply()
            refreshUi()
        }
        levelText = TextView(this)
        levelText.setTextColor(Color.WHITE)
        levelText.textSize = 18f
        levelText.setTypeface(levelText.typeface, Typeface.BOLD)
        levelText.gravity = Gravity.CENTER
        val plus = pill("+", 18f) {
            bfLevel = (bfLevel + 1).coerceAtMost(5)
            prefs.edit().putInt("bfLevel", bfLevel).apply()
            refreshUi()
        }
        lvRow.addView(minus)
        lvRow.addView(levelText, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
        lvRow.addView(plus)
        configBox.addView(lvRow)

        val slRow = row(10)
        slRow.addView(label("Slot pasukan"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        for (i in 1..5) {
            val b = pill("$i", 14f) {
                bfSlot = i
                prefs.edit().putInt("bfSlot", i).apply()
                refreshUi()
            }
            b.setPadding(dp(12), dp(9), dp(12), dp(9))
            slotBtns.add(b)
            slRow.addView(b, margin(2))
        }
        configBox.addView(slRow)
        panelRoot.addView(configBox)

        // pengaturan lanjutan (roda gigi): kecepatan
        settingsBox = LinearLayout(this)
        settingsBox.orientation = LinearLayout.VERTICAL
        settingsBox.visibility = View.GONE
        val spRow = row(10)
        spRow.addView(label("Kecepatan"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        for (i in speeds.indices) {
            val b = pill(speedLabel(i), 12f) {
                speedIdx = i
                prefs.edit().putInt("speed", i).apply()
                refreshUi()
            }
            b.setPadding(dp(10), dp(8), dp(10), dp(8))
            speedBtns.add(b)
            spRow.addView(b, margin(2))
        }
        settingsBox.addView(spRow)
        panelRoot.addView(settingsBox)

        // baris debug OCR (hanya saat bot jalan)
        dbgView = TextView(this)
        dbgView.setTextColor(0xFF7C8591.toInt())
        dbgView.textSize = 9f
        dbgView.maxLines = 3
        dbgView.setPadding(0, dp(8), 0, 0)
        dbgView.visibility = View.GONE
        panelRoot.addView(dbgView)

        // geser panel dengan menyeret judul
        titleBox.setOnTouchListener(object : View.OnTouchListener {
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
            dp(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        panelParams.gravity = Gravity.TOP or Gravity.START
        panelParams.x = dp(8)
        panelParams.y = dp(60)
        wm.addView(panelRoot, panelParams)
    }

    private fun refreshUi() {
        val bot = bfOn
        val gem = gemOn
        val running = bot || gem
        configBox.visibility = if (running) View.GONE else View.VISIBLE
        if (running) settingsBox.visibility = View.GONE
        dbgView.visibility = if (running) View.VISIBLE else View.GONE
        toggle.text = if (bot) "ON" else "OFF"
        toggle.background = roundBg(if (bot) 0xFF1E9E63.toInt() else 0xFF3A3F47.toInt(), 14)
        gemToggle.text = if (gem) "ON" else "OFF"
        gemToggle.background = roundBg(if (gem) 0xFF1E9E63.toInt() else 0xFF3A3F47.toInt(), 14)
        levelText.text = "$bfLevel"
        for (i in slotBtns.indices) {
            slotBtns[i].background =
                roundBg(if (bfSlot == i + 1) 0xFF2F6BFF.toInt() else 0xFF2B2F36.toInt(), 12)
        }
        for (i in speedBtns.indices) {
            speedBtns[i].background =
                roundBg(if (speedIdx == i) 0xFF2F6BFF.toInt() else 0xFF2B2F36.toInt(), 12)
        }
        status.text = note ?: if (bot) "$bfCount rally · $bfStatus"
            else if (gem) "$gemSteps geseran · $gemStatus"
            else "Siap. Atur level lalu nyalakan."
    }

    // ---------- Bot Farm Gems (cari gem deposit dengan menggeser peta) ----------

    private fun gemSetOn(on: Boolean) {
        if (on == gemOn) return
        if (on && !ocrSupported) {
            note = "Bot butuh Android 11 ke atas"
            refreshUi()
            return
        }
        if (on && bfOn) bfSetOn(false)
        gemOn = on
        if (on) {
            gm = Gm.CHECK
            gemBusy = false
            gemSteps = 0
            gemNull = 0
            gemWait = 0
            gemDir = 0
            gemLegLen = 1
            gemLegDone = 0
            gemLegsAtLen = 0
            gemStatus = "mulai"
            note = null
            gemHideMarker()
            refreshUi()
            panelAside()
            gemSchedule(500)
        } else {
            handler.removeCallbacks(gemTask)
            panelRoot.alpha = 1f
            gemStatus = ""
            panelBack()
            refreshUi()
        }
    }

    private fun gemSchedule(ms: Long) {
        handler.removeCallbacks(gemTask)
        val d = (ms / speeds[speedIdx]).toLong().coerceAtLeast(400L)
        if (gemOn) handler.postDelayed(gemTask, d)
    }

    private fun gemStop(msg: String) {
        gemSetOn(false)
        note = msg
        refreshUi()
    }

    private fun gemTick() {
        if (!gemOn) return
        if (gemBusy) {
            gemSchedule(1000)
            return
        }
        gemBusy = true
        when (gm) {
            Gm.CHECK -> readScreen { sc ->
                gemBusy = false
                if (!gemOn) return@readScreen
                if (sc == null) {
                    if (++gemNull >= 5) gemStop("Screenshot gagal, game mungkin memblokir")
                    else gemSchedule(1000)
                    return@readScreen
                }
                gemNull = 0
                gemW = sc.w
                gemH = sc.h
                if (mapVisible(sc)) {
                    gm = Gm.SCAN
                    gemWait = 0
                    gemStatus = "mencari gem"
                    refreshUi()
                    gemSchedule(300)
                } else if (++gemWait >= 8) {
                    gemStop("Buka peta dulu (bukan di dalam kota)")
                } else {
                    gemStatus = "menunggu peta"
                    refreshUi()
                    gemSchedule(2500)
                }
            }
            Gm.SCAN -> gemShot { hit, ok ->
                gemBusy = false
                if (!gemOn) return@gemShot
                if (!ok) {
                    if (++gemNull >= 5) gemStop("Screenshot gagal, game mungkin memblokir")
                    else gemSchedule(1000)
                    return@gemShot
                }
                gemNull = 0
                if (hit != null) {
                    gemHideMarker()
                    gemShowMarker(hit)
                    val n = gemSteps
                    gemStop("Gem deposit KETEMU (geseran ke-$n). Cek lingkaran merah di layar.")
                    return@gemShot
                }
                if (gemSteps >= gemMaxSteps) {
                    gemStop("Gem tidak ketemu setelah $gemMaxSteps geseran")
                    return@gemShot
                }
                gemMove()
            }
        }
    }

    // ambil screenshot (panel disembunyikan), analisis warna di thread terpisah
    private fun gemShot(cb: (GemHit?, Boolean) -> Unit) {
        panelRoot.alpha = 0f
        handler.postDelayed({
            grab { bmp ->
                panelRoot.alpha = 1f
                if (bmp == null) {
                    cb(null, false)
                    return@grab
                }
                gemExec.execute {
                    val hit = try { gemFind(bmp) } catch (e: Exception) { null }
                    bmp.recycle()
                    handler.post { cb(hit, true) }
                }
            }
        }, 150L)
    }

    private fun isGemRed(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        val mx = maxOf(r, maxOf(g, b))
        val mn = minOf(r, minOf(g, b))
        if (mx == 0 || r != mx) return false
        val d = mx - mn
        if (d == 0) return false
        val s = d.toFloat() / mx
        val v = mx / 255f
        var hue = ((g - b).toFloat() / d) * 60f
        if (hue < 0f) hue += 360f
        return (hue >= 340f || hue <= 12f) && s >= 0.45f && v >= 0.22f && v <= 0.95f
    }

    // cari gumpalan merah kristal (ciri gem deposit) di area peta; UI game (atas, bawah, kiri, kanan) diabaikan
    private fun gemFind(bmp: Bitmap): GemHit? {
        val w = bmp.width
        val h = bmp.height
        val stride = maxOf(2, w / 640)
        val gw = w / stride
        val gh = h / stride
        val red = BooleanArray(gw * gh)
        var total = 0
        for (y in 0 until gh) {
            val fy = y * stride / h.toFloat()
            if (fy < 0.17f || fy > 0.84f) continue
            for (x in 0 until gw) {
                val fx = x * stride / w.toFloat()
                if (fx < 0.17f || fx > 0.90f) continue
                if (isGemRed(bmp.getPixel(x * stride, y * stride))) {
                    red[y * gw + x] = true
                    total++
                }
            }
        }
        if (total == 0) return null

        // dilasi 2 kali (3x3) supaya sisi kristal yang terang tetap tersambung
        var cur = red
        for (n in 0 until 2) {
            val nx = BooleanArray(gw * gh)
            for (y in 0 until gh) for (x in 0 until gw) {
                if (!cur[y * gw + x]) continue
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = y + dy
                    val xx = x + dx
                    if (yy in 0 until gh && xx in 0 until gw) nx[yy * gw + xx] = true
                }
            }
            cur = nx
        }

        val seen = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        val minCount = (gw * gh * 0.0008f).toInt().coerceAtLeast(40)
        var best: GemHit? = null
        for (start in 0 until gw * gh) {
            if (!cur[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var cnt = 0
            var sx = 0L
            var sy = 0L
            var x0 = gw
            var x1 = 0
            var y0 = gh
            var y1 = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % gw
                val y = i / gw
                if (red[i]) {
                    cnt++
                    sx += x
                    sy += y
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = y + dy
                    val xx = x + dx
                    if (yy < 0 || yy >= gh || xx < 0 || xx >= gw) continue
                    val j = yy * gw + xx
                    if (cur[j] && !seen[j]) {
                        seen[j] = true
                        stack[sp++] = j
                    }
                }
            }
            if (cnt < minCount) continue
            val bw = (x1 - x0 + 1).toFloat() / gw
            val bh = (y1 - y0 + 1).toFloat() / gh
            val fill = cnt.toFloat() / ((x1 - x0 + 1) * (y1 - y0 + 1))
            val ratio = (bw * w) / (bh * h)
            if (bw < 0.03f || bw > 0.25f) continue
            if (fill < 0.10f) continue
            if (ratio < 0.3f || ratio > 3f) continue
            if (best == null || cnt > best.count) {
                best = GemHit((sx.toFloat() / cnt) * stride, (sy.toFloat() / cnt) * stride, cnt, bw, bh)
            }
        }
        return best
    }

    // geser peta satu langkah mengikuti pola spiral (1,1,2,2,3,3,... langkah per arah)
    private fun gemMove() {
        val dm = resources.displayMetrics
        val w = (if (gemW > 0) gemW else dm.widthPixels).toFloat()
        val h = (if (gemH > 0) gemH else dm.heightPixels).toFloat()
        val x1: Float
        val y1: Float
        val x2: Float
        val y2: Float
        when (gemDir) {
            0 -> { x1 = w * 0.8f; y1 = h * 0.5f; x2 = w * 0.2f; y2 = h * 0.5f }   // lihat ke kanan
            1 -> { x1 = w * 0.5f; y1 = h * 0.78f; x2 = w * 0.5f; y2 = h * 0.2f }  // lihat ke bawah
            2 -> { x1 = w * 0.2f; y1 = h * 0.5f; x2 = w * 0.8f; y2 = h * 0.5f }   // lihat ke kiri
            else -> { x1 = w * 0.5f; y1 = h * 0.2f; x2 = w * 0.5f; y2 = h * 0.78f } // lihat ke atas
        }
        gemSteps++
        gemStatus = "geser peta"
        refreshUi()

        gemLegDone++
        if (gemLegDone >= gemLegLen) {
            gemLegDone = 0
            gemDir = (gemDir + 1) % 4
            gemLegsAtLen++
            if (gemLegsAtLen >= 2) {
                gemLegsAtLen = 0
                gemLegLen++
            }
        }

        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 450L))
            .build()
        val after = {
            gemStatus = "mencari gem"
            gemSchedule(1100)
        }
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { after() }
            override fun onCancelled(gestureDescription: GestureDescription?) { after() }
        }, null)
        if (!ok) after()
    }

    // lingkaran merah sementara di posisi gem yang ketemu
    private fun gemShowMarker(hit: GemHit) {
        val d = maxOf(dp(60), (hit.bw * resources.displayMetrics.widthPixels * 1.4f).toInt())
        val v = View(this)
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(0x00000000)
        g.setStroke(dp(4), 0xFFFF2D2D.toInt())
        v.background = g
        val lp = WindowManager.LayoutParams(
            d, d,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (hit.cx - d / 2f).toInt()
        lp.y = (hit.cy - d / 2f).toInt()
        try {
            wm.addView(v, lp)
            gemMarker = v
            handler.postDelayed({ gemHideMarker() }, 8000L)
        } catch (e: Exception) {}
    }

    private fun gemHideMarker() {
        val v = gemMarker ?: return
        gemMarker = null
        try { wm.removeView(v) } catch (e: Exception) {}
    }

    // ---------- Bot Rally Barbarian Fort ----------

    private fun bfSetOn(on: Boolean) {
        if (on == bfOn) return
        if (on && !ocrSupported) {
            note = "Bot butuh Android 11 ke atas"
            refreshUi()
            return
        }
        if (on && gemOn) gemSetOn(false)
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
            panelAside()
            bfSchedule(500)
        } else {
            handler.removeCallbacks(bfTask)
            panelRoot.alpha = 1f
            bfStatus = ""
            panelBack()
            refreshUi()
        }
    }

    // geser panel ke tengah atas supaya tidak menutupi titik tap bot
    private fun panelAside() {
        savedX = panelParams.x
        savedY = panelParams.y
        panelParams.x = (resources.displayMetrics.widthPixels * 0.36f).toInt()
        panelParams.y = dp(2)
        try { wm.updateViewLayout(panelRoot, panelParams) } catch (e: Exception) {}
    }

    private fun panelBack() {
        panelParams.x = savedX
        panelParams.y = savedY
        try { wm.updateViewLayout(panelRoot, panelParams) } catch (e: Exception) {}
    }

    // jeda antar langkah diskalakan dengan pengaturan kecepatan
    private fun bfSchedule(ms: Long) {
        handler.removeCallbacks(bfTask)
        val d = (ms / speeds[speedIdx]).toLong().coerceAtLeast(400L)
        if (bfOn) handler.postDelayed(bfTask, d)
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
            handler.postDelayed({ tapSeq(pts, i + 1, done) }, (350L / speeds[speedIdx]).toLong().coerceAtLeast(150L))
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
        if (bfBusy) {
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
                bf = Bf.PREP
                bfTries = 0
                bfStatus = "pilih waktu"
                refreshUi()
                tapPx(ln.cx, ln.cy) { bfSchedule(1500) }
            }
            Bf.PREP -> {
                // dialog "Serangan Rally": waktu persiapan (pilihan game tetap dipakai), lalu tap RALLY kedua
                val dialog = sc.has("persiapan") || (sc.has("5 Menit") && sc.has("10 Menit"))
                if (dialog) {
                    val ln = sc.exactAny("RALLY")
                    bf = Bf.TROOPS
                    bfTries = 0
                    bfStatus = "pilih pasukan"
                    refreshUi()
                    if (ln != null) {
                        tapPx(ln.cx, ln.cy) { bfSchedule(1500) }
                    } else {
                        tapFrac(sc, 0.7873f, 0.4357f) { bfSchedule(1500) }
                    }
                    return
                }
                // kalau game langsung membuka layar pilih pasukan, lewati langkah ini
                if (sc.has("BERGERAK") || sc.has("dari kotamu")) {
                    bf = Bf.TROOPS
                    bfTries = 0
                    bfSchedule(100)
                    return
                }
                if (bfTries++ >= 8) {
                    bfFail("Dialog waktu rally tidak muncul", sc)
                } else {
                    bfSchedule(1000)
                }
            }
            Bf.TROOPS -> {
                // layar pilih pasukan sudah terbuka -> pilih slot
                if (sc.has("BERGERAK") || sc.has("March")) {
                    bf = Bf.GO
                    bfTries = 0
                    tapFrac(sc, 0.7899f, bfSlotY[bfSlot - 1]) { bfSchedule(900) }
                    return
                }
                // dialog waktu persiapan masih terbuka -> kembali ke langkah itu
                if (sc.has("persiapan")) {
                    bf = Bf.PREP
                    bfTries = 0
                    bfSchedule(500)
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
                if (sc.has("dari kotamu") || sc.has("Antrean")) {
                    if (bfTries >= 8) {
                        bfFail("Layar pasukan tidak muncul", sc)
                        return
                    }
                    val direct = bfTries % 2 == 0
                    bfTries++
                    if (direct) {
                        // popup biasanya sudah muncul: tap langsung tombol Pasukan Baru
                        tapFrac(sc, 0.8024f, 0.2f) { bfSchedule(1500) }
                    } else {
                        // popup mungkin sudah hilang: tap + dulu, lalu tombol Pasukan Baru
                        tapFrac(sc, 0.9306f, 0.1475f) {
                            handler.postDelayed({
                                if (bfOn) tapFrac(sc, 0.8024f, 0.2f) { bfSchedule(1500) }
                            }, 800L)
                        }
                    }
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

}
