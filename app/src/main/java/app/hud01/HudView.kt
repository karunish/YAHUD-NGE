package app.hud01

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class LinkState { NONE, CONNECTING, LIVE }

private object C {
    val VOID = 0xFF000000.toInt()
    val SURFACE = 0xFF0A0F0E.toInt()
    val LINE = 0xFF34413D.toInt()
    val WHITE = 0xFFE8EEE9.toInt()
    val MUTED = 0xFF929E98.toInt()
    val GREEN = 0xFF72FF78.toInt()
    val ORANGE = 0xFFFF8A28.toInt()
    val RED = 0xFFFF343B.toInt()
    val CYAN = 0xFF52E8EE.toInt()
    val REDBG = 0xFF1A0002.toInt()
}

/** The whole HUD, drawn on one canvas. Mirror mode flips the HUD horizontally for windshield reflection. */
class HudView(context: Context) : View(context) {
    var state = VehicleState()
        set(v) {
            field = v
            val now = SystemClock.uptimeMillis()
            cltTrend.add(now, v.coolantC)
            oilTrend.add(now, v.oilC)
        }
    var sample = true
    var mirror = false
    var link = LinkState.NONE
        set(v) {
            field = v
            resetTrends()
        }
    var onLink: (() -> Unit)? = null
    private val lines = ArrayList<Pair<String, Level>>()

    private var barUntil = 0L
    private val cltTrend = Trend()
    private val oilTrend = Trend()
    private val d = resources.displayMetrics.density
    private val cond: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val serif: Typeface = Typeface.create("serif", Typeface.BOLD)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val animOn =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    private val hits = ArrayList<Pair<RectF, Int>>()

    private fun txt(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, tf: Typeface = cond, ls: Float = 0f
    ) {
        p.style = Paint.Style.FILL
        p.typeface = tf
        p.textSize = size
        p.color = color
        p.textAlign = align
        p.letterSpacing = ls
        c.drawText(s, x, y, p)
    }

    private fun box(c: Canvas, r: RectF, fill: Int?, stroke: Int?, sw: Float) {
        if (fill != null) {
            p.style = Paint.Style.FILL
            p.color = fill
            c.drawRect(r, p)
        }
        if (stroke != null) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = sw
            p.color = stroke
            c.drawRect(r, p)
        }
    }

    private fun resetTrends() {
        cltTrend.reset()
        oilTrend.reset()
    }

    fun log(msg: String, level: Level = Level.NOMINAL) {
        lines.add(Pair(msg, level))
        if (lines.size > 200) lines.removeAt(0)
        invalidate()
    }

    private fun toggleSample() {
        if (link != LinkState.NONE) {
            log("Unlink the adapter before starting sample data", Level.CAUTION)
            return
        }
        sample = !sample
        resetTrends()
        if (!sample) state = VehicleState()
    }

    private fun toggleMirror() {
        mirror = !mirror
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        val s = state
        val alert = s.alert()
        val pad = 16 * d
        val barH = 52 * d
        val barOn = !mirror || now < barUntil
        c.drawColor(C.VOID)
        hits.clear()

        c.save()
        if (mirror) c.scale(-1f, 1f, w / 2, h / 2)

        val rowBottom = h - pad - (if (mirror) 0f else barH)
        val rowH = if (alert != null) 56 * d else if (mirror) 0f else 92 * d
        val rowTop = rowBottom - rowH
        val tickBase = rowTop - 8 * d
        val rpmBottom = tickBase - 18 * d
        val rpmTop = rpmBottom - 34 * d
        val rpmBase = rpmTop - 10 * d
        val areaTop = if (mirror) pad else 60 * d
        val areaH = rpmBase - 34 * d - areaTop

        if (!mirror) {
            header(c, w, pad)
            statusLine(c, pad)
        }
        speed(c, s, w, areaTop, areaH)
        rpmBar(c, s, w, pad, rpmTop, rpmBottom, rpmBase, tickBase)
        if (alert != null) banner(c, alert, pad, rowTop, w - pad, rowBottom, now)
        else if (!mirror) cells(c, s, pad, rowTop, w - pad, rowBottom)
        c.restore()

        if (barOn) bottomBar(c, w, h, barH)
    }

    private fun header(c: Canvas, w: Float, pad: Float) {
        txt(c, "UNIT F30-318i / HUD-01", pad, 26 * d, 12 * d, C.ORANGE, ls = 0.08f)
        val (linkText, lc) = when {
            link == LinkState.LIVE -> Pair("LINK: LIVE", C.GREEN)
            link == LinkState.CONNECTING -> Pair("LINK: CONNECTING", C.ORANGE)
            sample -> Pair("LINK: SAMPLE DATA", C.ORANGE)
            else -> Pair("LINK: NONE", C.MUTED)
        }
        box(c, RectF(w / 2 - 92 * d, 17 * d, w / 2 - 84 * d, 25 * d), lc, null, 0f)
        txt(c, linkText, w / 2 - 78 * d, 26 * d, 12 * d, lc, ls = 0.08f)
        txt(c, clock.format(Date()), w - pad, 26 * d, 12 * d, C.MUTED, Paint.Align.RIGHT, ls = 0.08f)
        p.style = Paint.Style.FILL
        p.color = C.LINE
        c.drawRect(pad, 34 * d, w - pad, 35 * d, p)
    }

    private fun statusLine(c: Canvas, pad: Float) {
        val last = lines.lastOrNull() ?: return
        val col = when (last.second) {
            Level.FAULT -> C.RED
            Level.CAUTION -> C.ORANGE
            else -> C.MUTED
        }
        txt(c, last.first, pad, 54 * d, 13 * d, col, tf = Typeface.MONOSPACE)
    }

    private fun speed(c: Canvas, s: VehicleState, w: Float, top: Float, h: Float) {
        val str = s.speedKmh?.roundToInt()?.toString() ?: "--"
        var size = h * 1.25f
        p.typeface = cond
        p.letterSpacing = 0f
        p.textSize = size
        val maxW = w * 0.55f
        val tw0 = p.measureText(str)
        if (tw0 > maxW) size *= maxW / tw0
        p.textSize = size
        val tw = p.measureText(str)
        val base = top + h / 2 + size * 0.36f
        txt(c, str, w / 2, base, size, C.WHITE, Paint.Align.CENTER)
        txt(c, "KM/H", w / 2 + tw / 2 + 14 * d, base, 18 * d, C.MUTED, ls = 0.08f)
    }

    private fun rpmBar(c: Canvas, s: VehicleState, w: Float, pad: Float, top: Float, bottom: Float, base: Float, tickBase: Float) {
        val n = 60
        val gap = 2 * d
        val sw = (w - 2 * pad - gap * (n - 1)) / n
        val rpm = s.rpm
        val lit = if (rpm == null) 0 else (RpmScale.fraction(rpm) * n).roundToInt().coerceIn(0, n)
        for (i in 0 until n) {
            val x = pad + i * (sw + gap)
            val r = RectF(x, top, x + sw, bottom)
            if (i < lit) box(c, r, C.WHITE, null, 0f) else box(c, r, C.SURFACE, C.LINE, 1f)
        }
        // Ticks sit at their true positions on the non-linear dial: 500 rpm minor, 1000 rpm major.
        val span = w - 2 * pad
        var k = 0
        while (k <= 7000) {
            val x = pad + RpmScale.fraction(k.toFloat()) * span
            val major = k % 1000 == 0
            p.style = Paint.Style.FILL
            p.color = if (major) C.WHITE else C.MUTED
            c.drawRect(x - d / 2, bottom + 2 * d, x + d / 2, bottom + (if (major) 8 else 5) * d, p)
            if (major) txt(c, (k / 1000).toString(), x, tickBase + 2 * d, 11 * d, C.MUTED, Paint.Align.CENTER)
            k += 500
        }
        txt(c, rpm?.let { "${it.roundToInt()}" } ?: "--", w - pad - 46 * d, base, 28 * d, C.WHITE, Paint.Align.RIGHT)
        txt(c, "RPM", w - pad, base, 14 * d, C.MUTED, Paint.Align.RIGHT, ls = 0.08f)
        txt(c, "ENGINE SPEED / NON-LINEAR DIAL x1000", pad, base, 12 * d, C.MUTED, ls = 0.08f)
        if (rpm != null && rpm > RpmScale.DIAL_MAX) {
            txt(c, "OVER DIAL SCALE", w / 2, base, 14 * d, C.WHITE, Paint.Align.CENTER, ls = 0.08f)
        }
    }

    private fun tone(v: Float?, l: Level): Int =
        if (v == null) C.MUTED else when (l) {
            Level.FAULT -> C.RED
            Level.CAUTION -> C.ORANGE
            else -> C.GREEN
        }

    private class Cell(val label: String, val value: String, val color: Int, val sub1: String = "", val sub2: String = "")

    private fun fmt(s: VehicleState, key: String, v: Float?, unit: String, dec: Int = 0): String =
        if (v == null) (if (s.unsupported.contains(key)) "N/A" else "--")
        else if (dec == 0) "${v.roundToInt()}$unit"
        else String.format(Locale.US, "%.${dec}f", v) + unit

    private fun trendText(t: Trend): String {
        val r = t.perMinute() ?: return "TREND --"
        val a = abs(r)
        return if (a < 0.5f) "STEADY" else (if (r > 0f) "RISING " else "FALLING ") + String.format(Locale.US, "%.1f", a) + "/MIN"
    }

    /** Temperatures are information only: value, trend and trip maximum. No limits are applied. */
    private fun tempCell(label: String, s: VehicleState, key: String, v: Float?, tr: Trend): Cell {
        val value = fmt(s, key, v, "\u00b0C")
        if (v == null) return Cell(label, value, C.MUTED, if (s.unsupported.contains(key)) "NOT SUPPORTED BY CAR" else "")
        return Cell(label, value, C.WHITE, trendText(tr), tr.max?.let { "MAX ${it.roundToInt()}\u00b0C" } ?: "")
    }

    private fun cells(c: Canvas, s: VehicleState, l: Float, t: Float, r: Float, b: Float) {
        val items = listOf(
            tempCell("COOLANT", s, "clt", s.coolantC, cltTrend),
            tempCell("OIL", s, "oil", s.oilC, oilTrend),
            Cell("LOAD", fmt(s, "load", s.loadPct, "%"), if (s.loadPct == null) C.MUTED else C.WHITE),
            Cell("MAP ABS", fmt(s, "map", s.mapKpa, " kPa"), if (s.mapKpa == null) C.MUTED else C.CYAN),
            Cell("VOLTS", fmt(s, "vlt", s.volts, " V", 1), tone(s.volts, s.voltLevel())),
        )
        val gap = 6 * d
        val cw = (r - l - gap * (items.size - 1)) / items.size
        for ((i, item) in items.withIndex()) {
            val x = l + i * (cw + gap)
            box(c, RectF(x, t, x + cw, b), C.SURFACE, C.LINE, 1f)
            txt(c, item.label, x + 10 * d, t + 18 * d, 11 * d, C.MUTED, ls = 0.08f)
            txt(c, item.value, x + 10 * d, t + 52 * d, 28 * d, item.color)
            if (item.sub1.isNotEmpty()) txt(c, item.sub1, x + 10 * d, t + 70 * d, 11 * d, C.MUTED, ls = 0.04f)
            if (item.sub2.isNotEmpty()) txt(c, item.sub2, x + 10 * d, t + 84 * d, 11 * d, C.MUTED, ls = 0.04f)
        }
    }

    private fun banner(c: Canvas, a: Alert, l: Float, t: Float, r: Float, b: Float, now: Long) {
        val fault = a.level == Level.FAULT
        val col = if (fault) C.RED else C.ORANGE
        val dim = fault && animOn && (now / 500) % 2L == 1L
        val shown = if (dim) (col and 0x00FFFFFF) or (0x5A shl 24) else col
        box(c, RectF(l, t, r, b), if (fault) C.REDBG else null, shown, 2 * d)
        box(c, RectF(l, t, l + 96 * d, b), shown, null, 0f)
        // \u8b66\u544a = the Japanese word for "warning"
        txt(c, "\u8b66\u544a", l + 48 * d, (t + b) / 2 + 9 * d, 26 * d, C.VOID, Paint.Align.CENTER, serif)
        txt(c, a.text, l + 114 * d, (t + b) / 2 + 8 * d, 22 * d, shown)
    }

    private fun bottomBar(c: Canvas, w: Float, h: Float, bh: Float) {
        val top = h - bh
        box(c, RectF(0f, top, w, h), C.VOID, null, 0f)
        p.style = Paint.Style.FILL
        p.color = C.LINE
        c.drawRect(0f, top, w, top + d, p)
        val names = listOf(if (link == LinkState.NONE) "LINK" else "UNLINK", "SAMPLE", "MIRROR")
        val on = listOf(link == LinkState.LIVE, sample, mirror)
        val bw = w / names.size
        for (i in names.indices) {
            val r = RectF(i * bw, top, (i + 1) * bw, h)
            hits.add(Pair(r, i))
            val col = if (on[i]) C.GREEN else C.WHITE
            txt(c, names[i], r.centerX(), r.centerY() + 6 * d, 16 * d, col, Paint.Align.CENTER, ls = 0.1f)
            p.style = Paint.Style.FILL
            if (on[i]) {
                p.color = C.GREEN
                c.drawRect(r.left, h - 3 * d, r.right, h, p)
            }
            if (i > 0) {
                p.color = C.LINE
                c.drawRect(r.left, top, r.left + d, h, p)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val hit = hits.firstOrNull { it.first.contains(e.x, e.y) }
            when (hit?.second) {
                0 -> onLink?.invoke()
                1 -> toggleSample()
                2 -> toggleMirror()
            }
            if (mirror) barUntil = SystemClock.uptimeMillis() + 4000
            invalidate()
        }
        return true
    }
}
