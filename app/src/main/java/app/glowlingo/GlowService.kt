package app.glowlingo

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

/** Yıldızlı kelimelerin ekranda parlamasını çizen, sadece dokunulmayan bir katman. */
class GlowView(c: Context) : View(c) {
    private var rects: List<RectF> = emptyList()
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFC107 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFC107 }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xCCFFC107.toInt()
    }

    fun set(r: List<RectF>) {
        if (r.isEmpty() && rects.isEmpty()) return
        rects = r
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        cv.translate(-loc[0].toFloat(), -loc[1].toFloat())
        for (r in rects) {
            val a = RectF(r).apply { inset(-9f, -6f) }
            val b = RectF(r).apply { inset(-4f, -2f) }
            cv.drawRoundRect(a, 14f, 14f, halo)
            cv.drawRoundRect(b, 10f, 10f, fill)
            cv.drawRoundRect(b, 10f, 10f, edge)
        }
    }
}

class GlowService : AccessibilityService() {
    companion object {
        @Volatile var inst: GlowService? = null
        private val TOK = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}]+)?")
    }

    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var gen = 0
    private var lastScan = 0L

    private var glow: GlowView? = null
    private var bubble: TextView? = null
    private var cap: View? = null
    private var pop: LinearLayout? = null
    private var tvTr: TextView? = null
    private var tvEx: TextView? = null
    private var chipStar: TextView? = null
    private var popText = ""
    private var popTr = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- yaşam döngüsü

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        Db.init(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        inst = this
        val g = GlowView(this)
        wm.addView(g, overlayLp(false, -1, -1))
        glow = g
        applyEnabled()
        Tx.warm()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        h.removeCallbacksAndMessages(null)
        hidePopup(); exitCapture(); removeBubble()
        glow?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        glow = null
        inst = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    /** QS tile / ayarlar buradan açıp kapatır. */
    fun applyEnabled() {
        if (Prefs.enabled) {
            showBubble(); scheduleScan(50)
        } else {
            exitCapture(); hidePopup(); removeBubble(); glow?.set(emptyList())
        }
    }

    fun rescan() = scheduleScan(50)

    // ---------------------------------------------------------------- olaylar

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (!Prefs.enabled) return
        if (e.packageName?.toString() == packageName) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val from = e.fromIndex
                val to = e.toIndex
                if (from >= 0 && to - from in 1..80) {
                    val t = (e.source?.text ?: e.text.firstOrNull())?.toString()
                    if (t != null && to <= t.length) {
                        val s = t.substring(from, to).trim()
                        if (s.isNotEmpty()) h.post { showPopup(s) }
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                glow?.set(emptyList())          // kaydırırken eski konumlar yanlış kalmasın
                scheduleScan(250)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scheduleScan(300)
        }
    }

    // ---------------------------------------------------------------- parlatma taraması

    private val scanRun = Runnable { doScan() }

    private fun scheduleScan(delay: Long) {
        h.removeCallbacks(scanRun)
        h.postDelayed(scanRun, delay)
    }

    private fun doScan() {
        if (!Prefs.enabled || !Prefs.glow || Db.size() == 0) { glow?.set(emptyList()); return }
        val now = System.currentTimeMillis()
        if (now - lastScan < 400) { scheduleScan(400); return }   // CPU koruması
        lastScan = now
        val root = rootInActiveWindow ?: return
        val g = ++gen
        worker.execute {
            val out = ArrayList<RectF>()
            scanNode(root, out, 0, IntArray(1), g)
            h.post { if (g == gen) glow?.set(out) }
        }
    }

    private fun scanNode(n: AccessibilityNodeInfo, out: MutableList<RectF>, depth: Int, cnt: IntArray, g: Int) {
        if (cnt[0]++ > 2500 || depth > 45 || out.size > 80 || g != gen) return
        if (!n.isVisibleToUser) return
        if (n.packageName?.toString() == packageName) return
        val t = n.text
        if (!t.isNullOrEmpty() && t.length < 5000 && !n.isPassword) matchNode(n, t.toString(), out)
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            scanNode(c, out, depth + 1, cnt, g)
        }
    }

    private fun matchNode(n: AccessibilityNodeInfo, t: String, out: MutableList<RectF>) {
        var hits: ArrayList<IntArray>? = null
        for (m in TOK.findAll(t)) {
            if (Db.has(m.value.lowercase(Locale.ROOT))) {
                val l = hits ?: ArrayList<IntArray>().also { hits = it }
                l.add(intArrayOf(m.range.first, m.range.last + 1))
            }
        }
        val hs = hits ?: return
        for (hi in hs) {
            if (out.size > 80) break
            rectFor(n, hi[0], hi[1], t.length)?.let { out.add(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun charRects(n: AccessibilityNodeInfo, s: Int, len: Int): Array<RectF?>? {
        val args = Bundle()
        args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, s)
        args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, len)
        if (!n.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)) return null
        val arr = n.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY) ?: return null
        return Array(arr.size) { arr[it] as? RectF }
    }

    private fun rectFor(n: AccessibilityNodeInfo, s: Int, e: Int, len: Int): RectF? {
        val rs = charRects(n, s, e - s)
        if (rs != null) {
            var u: RectF? = null
            for (r in rs) {
                if (r == null) continue
                if (u == null) u = RectF(r) else u.union(r)
            }
            if (u != null && !u.isEmpty) return u
        }
        // Yedek: tek satırlık düğümde karakter oranıyla tahmin
        val b = Rect()
        n.getBoundsInScreen(b)
        if (b.isEmpty || b.height() > dp(56)) return null
        val w = b.width().toFloat()
        return RectF(b.left + w * s / len, b.top.toFloat(), b.left + w * e / len, b.bottom.toFloat())
    }

    // ---------------------------------------------------------------- uzun bas modu (seçilemeyen metinler)

    private fun lookupAt(x: Int, y: Int) {
        val root = rootInActiveWindow ?: return
        worker.execute {
            val n = hitNode(root, x, y, 0)
            val w = if (n != null) wordAt(n, x, y) else null
            h.post { if (w != null) showPopup(w) }
        }
    }

    private fun hitNode(n: AccessibilityNodeInfo, x: Int, y: Int, depth: Int): AccessibilityNodeInfo? {
        if (depth > 45 || !n.isVisibleToUser) return null
        if (n.packageName?.toString() == packageName) return null
        val r = Rect()
        n.getBoundsInScreen(r)
        if (!r.contains(x, y)) return null
        for (i in n.childCount - 1 downTo 0) {
            val c = n.getChild(i) ?: continue
            hitNode(c, x, y, depth + 1)?.let { return it }
        }
        val t = n.text
        return if (!t.isNullOrEmpty() && !n.isPassword) n else null
    }

    private fun wordAt(n: AccessibilityNodeInfo, x: Int, y: Int): String? {
        val t = n.text?.toString() ?: return null
        if (t.length > 4000) return null
        var idx = -1
        val rs = charRects(n, 0, t.length)
        if (rs != null) {
            var best = dp(28).toFloat()
            for (i in rs.indices) {
                val r = rs[i] ?: continue
                val dx = maxOf(r.left - x, 0f, x - r.right)
                val dy = maxOf(r.top - y, 0f, y - r.bottom)
                val d = hypot(dx, dy)
                if (d < best) { best = d; idx = i }
            }
        }
        if (idx < 0) {
            val b = Rect()
            n.getBoundsInScreen(b)
            idx = ((x - b.left).toFloat() / b.width().coerceAtLeast(1) * t.length).toInt().coerceIn(0, t.length - 1)
        }
        return tokenAt(t, idx)
    }

    private fun isW(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’'

    private fun tokenAt(t: String, i: Int): String? {
        var p = i.coerceIn(0, t.length - 1)
        if (!isW(t[p])) {
            if (p > 0 && isW(t[p - 1])) p-- else if (p + 1 < t.length && isW(t[p + 1])) p++ else return null
        }
        var s = p
        var e = p + 1
        while (s > 0 && isW(t[s - 1])) s--
        while (e < t.length && isW(t[e])) e++
        val w = t.substring(s, e).trim('\'', '’')
        return if (w.isEmpty() || w.all { it.isDigit() }) null else w
    }

    private fun toggleCapture() { if (cap == null) enterCapture() else exitCapture() }

    private fun enterCapture() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                cap?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                lookupAt(e.rawX.toInt(), e.rawY.toInt())
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean { hidePopup(); return true }
        })
        val v = object : View(this) {
            override fun onTouchEvent(e: MotionEvent): Boolean { gd.onTouchEvent(e); return true }
        }
        v.setBackgroundColor(0x0F1A73E8)
        wm.addView(v, overlayLp(true, -1, -1))
        cap = v
        refreshBubble()
        Toast.makeText(this, "Çeviri modu: kelimeye uzun bas · çıkmak için balona dokun", Toast.LENGTH_SHORT).show()
    }

    private fun exitCapture() {
        cap?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        cap = null
        if (bubble != null) refreshBubble()
    }

    // ---------------------------------------------------------------- kayan balon

    private fun refreshBubble() { removeBubble(); if (Prefs.enabled) showBubble() }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        bubble = null
    }

    private fun showBubble() {
        if (bubble != null) return
        val on = cap != null
        val b = TextView(this).apply {
            text = "文"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            alpha = 0.92f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) 0xFF188038.toInt() else 0xFF1A73E8.toInt())
            }
        }
        val size = dp(48)
        val p = overlayLp(true, size, size)
        val dm = resources.displayMetrics
        p.x = if (Prefs.bx >= 0) Prefs.bx else dm.widthPixels - size - dp(4)
        p.y = if (Prefs.by >= 0) Prefs.by else (dm.heightPixels * 0.4f).toInt()
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = p.x; sy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val ox = e.rawX - dx
                    val oy = e.rawY - dy
                    if (abs(ox) > dp(6) || abs(oy) > dp(6)) moved = true
                    if (moved) { p.x = (sx + ox).toInt(); p.y = (sy + oy).toInt(); wm.updateViewLayout(v, p) }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) { Prefs.bx = p.x; Prefs.by = p.y } else toggleCapture()
                }
            }
            true
        }
        wm.addView(b, p)
        bubble = b
    }

    // ---------------------------------------------------------------- çeviri kartı

    private val hideRun = Runnable { hidePopup() }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(0xFF8AB4F8.toInt()); textSize = 14f
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
    }

    fun showPopup(text: String) {
        hidePopup()
        popText = text.lowercase(Locale.ROOT)
        popTr = ""
        val single = text.none { it.isWhitespace() }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xF2202124.toInt()) }
        }
        card.addView(TextView(this).apply { this.text = text; setTextColor(0xFF9AA0A6.toInt()); textSize = 13f })
        val tr = TextView(this).apply { this.text = "…"; setTextColor(Color.WHITE); textSize = 20f }
        val ex = TextView(this).apply { setTextColor(0xFFBDC1C6.toInt()); textSize = 13f; visibility = View.GONE }
        card.addView(tr); card.addView(ex)
        tvTr = tr; tvEx = ex

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        if (single) {
            val star = chip("") { toggleStar() }
            chipStar = star
            row.addView(star)
            updateStar()
        }
        val swap = chip("${Prefs.src}⇄${Prefs.tgt}".uppercase()) {
            val s = Prefs.src; Prefs.src = Prefs.tgt; Prefs.tgt = s; showPopup(text)
        }
        val next = chip("${Prefs.tgt.uppercase()} ▸") {
            val codes = LANGS.map { it.first }
            var i = (codes.indexOf(Prefs.tgt) + 1) % codes.size
            if (codes[i] == Prefs.src) i = (i + 1) % codes.size
            Prefs.tgt = codes[i]; showPopup(text)
        }
        row.addView(swap); row.addView(next)
        card.addView(row)

        card.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) hidePopup()
            false
        }

        val p = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(24), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        p.y = dp(88)
        wm.addView(card, p)
        pop = card
        h.postDelayed(hideRun, 10000)

        Tx.translate(text) { r ->
            if (pop !== card) return@translate
            popTr = r?.main ?: ""
            tr.text = if (r != null) r.main else "Çeviri alınamadı"
            ex.text = r?.extra ?: ""
            ex.visibility = if (ex.text.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun updateStar() {
        chipStar?.text = if (Db.has(popText)) "★ Kayıtlı" else "☆ Kaydet"
    }

    private fun toggleStar() {
        if (popText.isEmpty()) return
        if (Db.has(popText)) Db.remove(popText) else Db.add(popText, popTr)
        updateStar()
        scheduleScan(50)
    }

    private fun hidePopup() {
        h.removeCallbacks(hideRun)
        pop?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        pop = null
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun overlayLp(touchable: Boolean, w: Int, ht: Int): WindowManager.LayoutParams {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(w, ht, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, f, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }
}

val LANGS = listOf(
    "en" to "English", "tr" to "Türkçe", "de" to "Deutsch", "fr" to "Français",
    "es" to "Español", "it" to "Italiano", "pt" to "Português", "ru" to "Русский",
    "ar" to "العربية", "ja" to "日本語", "ko" to "한국어", "nl" to "Nederlands",
    "pl" to "Polski", "uk" to "Українська"
)
