package app.hud01

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class LinkState { NONE, CONNECTING, LIVE }

/** Evangelion-inspired palette: EVA-01 purple and neon green, NERV orange, on black. */
private object C {
    val VOID = 0xFF000000.toInt()
    val SURFACE = 0xFF0B0713.toInt()
    val LINE = 0xFF3A2A5C.toInt()
    val WHITE = 0xFFEEEAF4.toInt()
    val MUTED = 0xFFA79FBD.toInt()
    val GREEN = 0xFF8CFF3C.toInt()
    val PURPLE = 0xFFA45CFF.toInt()
    val DEEP = 0xFF4A2A85.toInt()
    val ORANGE = 0xFFFF7A1A.toInt()
    val RED = 0xFFFF343B.toInt()
    val CYAN = 0xFF52E8EE.toInt()
    val REDBG = 0xFF1A0002.toInt()
}

private const val ID_LINK = 0
private const val ID_SAMPLE = 1
private const val ID_MIRROR = 2
private const val ID_LAYOUT = 3
private const val ID_SMALLER = 4
private const val ID_BIGGER = 5
private const val ID_RESET = 6
private const val ID_RESET_ALL = 7
private const val ID_DONE = 8
private const val ID_NOTICE = 10

/**
 * The whole HUD, drawn on one canvas. Mirror mode flips it horizontally for windshield reflection and
 * drops the decorative graphics so only speed and RPM glow. Every element can be moved and resized in
 * LAYOUT mode; positions are saved separately for normal and mirror view.
 */
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

    /** Persistent reminder shown in the bottom row until dismissed (tap it) or cleared by the caller. */
    var notice: String? = null

    private val lines = ArrayList<Pair<String, Level>>()
    private var barUntil = 0L
    private val cltTrend = Trend()
    private val oilTrend = Trend()
    private val d = resources.displayMetrics.density
    private val barH = 52 * d
    private val cond: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val serif: Typeface = Typeface.create("serif", Typeface.BOLD)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val animOn =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    private val hits = ArrayList<Pair<RectF, Int>>()

    // Layout state
    private val layout = LayoutStore(context)
    private var edit = false
    private var sel: El? = null
    private var dragging = false
    private var lx = 0f
    private var ly = 0f
    private val bases = Array(El.values().size) { RectF() }
    private val safe = RectF()
    private val bannerR = RectF()
    private val tmp = RectF()
    private val cellEls = listOf(El.CLT, El.OIL, El.LOAD, El.MAP, El.VOLT)

    // Reusable paths
    private val path = Path()
    private val hz = Path()
    private val hexP = Path()
    private val segP = Path()
    private val hexLine = Path()
    private val hexFill = Path()
    private val dash = DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f)

    private class Btn(val label: String, val on: Boolean, val id: Int)
    private class Cell(
        val label: String, val jp: String, val value: String, val color: Int,
        val sub1: String = "", val sub2: String = "",
    )

    // ---------------------------------------------------------------- helpers

    private fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

    private fun txt(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, tf: Typeface = cond, ls: Float = 0f, sx: Float = 1f,
    ) {
        p.style = Paint.Style.FILL
        p.typeface = tf
        p.textSize = size
        p.color = color
        p.textAlign = align
        p.letterSpacing = ls
        p.textScaleX = sx
        c.drawText(s, x, y, p)
    }

    /** Draws text, shrinking it if needed so it never runs past maxW (nothing may be cut off). */
    private fun txtFit(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, maxW: Float,
        align: Paint.Align = Paint.Align.LEFT, tf: Typeface = cond, ls: Float = 0f, sx: Float = 1f,
    ) {
        val tw = measure(s, size, tf, ls, sx)
        val sz = if (tw > maxW && tw > 0f) size * maxW / tw else size
        txt(c, s, x, y, sz, color, align, tf, ls, sx)
    }

    private fun measure(s: String, size: Float, tf: Typeface, ls: Float, sx: Float): Float {
        p.typeface = tf
        p.textSize = size
        p.letterSpacing = ls
        p.textScaleX = sx
        return p.measureText(s)
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

    /** Rectangle with the top-left and bottom-right corners cut at 45 degrees. */
    private fun panel(c: Canvas, r: RectF, fill: Int?, stroke: Int?, sw: Float, cut: Float) {
        path.reset()
        path.moveTo(r.left + cut, r.top)
        path.lineTo(r.right, r.top)
        path.lineTo(r.right, r.bottom - cut)
        path.lineTo(r.right - cut, r.bottom)
        path.lineTo(r.left, r.bottom)
        path.lineTo(r.left, r.top + cut)
        path.close()
        if (fill != null) {
            p.style = Paint.Style.FILL
            p.color = fill
            c.drawPath(path, p)
        }
        if (stroke != null) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = sw
            p.color = stroke
            c.drawPath(path, p)
        }
    }

    private fun hexPath(out: Path, cx: Float, cy: Float, r: Float, rotDeg: Float) {
        for (i in 0 until 6) {
            val a = (60f * i + rotDeg) * 0.017453292f
            val x = cx + r * cos(a)
            val y = cy + r * sin(a)
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
    }

    /** Diagonal warning stripes, [a] on [b]. [frac] 0..1 scrolls them by one period. */
    private fun hazard(c: Canvas, r: RectF, a: Int, b: Int, frac: Float) {
        c.save()
        c.clipRect(r)
        p.style = Paint.Style.FILL
        p.color = b
        c.drawRect(r, p)
        p.color = a
        val hh = r.height()
        val period = hh * 2f
        var x = r.left - hh - period + frac * period
        while (x < r.right) {
            hz.reset()
            hz.moveTo(x, r.bottom)
            hz.lineTo(x + hh, r.bottom)
            hz.lineTo(x + hh + hh, r.top)
            hz.lineTo(x + hh, r.top)
            hz.close()
            c.drawPath(hz, p)
            x += period
        }
        c.restore()
    }

    private fun brackets(c: Canvas, r: RectF, len: Float, color: Int) {
        p.style = Paint.Style.FILL
        p.color = color
        val t = 2 * d
        c.drawRect(r.left, r.top, r.left + len, r.top + t, p)
        c.drawRect(r.left, r.top, r.left + t, r.top + len, p)
        c.drawRect(r.right - len, r.top, r.right, r.top + t, p)
        c.drawRect(r.right - t, r.top, r.right, r.top + len, p)
        c.drawRect(r.left, r.bottom - t, r.left + len, r.bottom, p)
        c.drawRect(r.left, r.bottom - len, r.left + t, r.bottom, p)
        c.drawRect(r.right - len, r.bottom - t, r.right, r.bottom, p)
        c.drawRect(r.right - t, r.bottom - len, r.right, r.bottom, p)
    }

    // ---------------------------------------------------------------- state changes

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
        sel = null
    }

    private fun toggleEdit() {
        edit = !edit
        sel = null
        dragging = false
        if (edit) {
            log("Layout edit: tap an element, drag to move, SMALLER / BIGGER to resize", Level.GOOD)
            if (mirror) log("Mirror is on, so the screen is flipped while you edit", Level.CAUTION)
        } else {
            layout.save(false)
            layout.save(true)
            log("Layout saved")
        }
    }

    private fun adjust(f: Float) {
        val e = sel
        if (e == null) {
            log("Tap an element first", Level.CAUTION)
            return
        }
        val pl = layout.get(mirror, e)
        pl.s = (pl.s * f).coerceIn(LayoutStore.MIN_SCALE, LayoutStore.MAX_SCALE)
        clampPl(pl, bases[e.ordinal])
        layout.save(mirror)
    }

    private fun resetSel() {
        val e = sel
        if (e == null) {
            log("Tap an element first, or use RESET ALL", Level.CAUTION)
            return
        }
        layout.reset(mirror, e)
    }

    // ---------------------------------------------------------------- layout

    private fun isVisible(e: El) = !mirror || e == El.SPEED || e == El.RPM

    /** Default (unmoved) rectangle of every element, kept inside the display cutout and rounded corners. */
    private fun computeLayout(w: Float, h: Float, rowActive: Boolean) {
        val pad = 14 * d
        var sl = 0f
        var sr = 0f
        var st = 0f
        val dc = rootWindowInsets?.displayCutout
        if (dc != null) {
            sl = dc.safeInsetLeft.toFloat()
            sr = dc.safeInsetRight.toFloat()
            st = dc.safeInsetTop.toFloat()
        }
        if (mirror) {
            val t = sl
            sl = sr
            sr = t
        }
        val l = sl + pad
        val r = w - sr - pad
        val t = st + 8 * d
        val b = h - (if (mirror) 0f else barH) - 8 * d
        safe.set(l, t, r, h - (if (mirror) 0f else barH))

        val cellsH = 80 * d
        val bannerH = 64 * d
        val hdrH = 58 * d
        val rpmH = 76 * d
        bannerR.set(l, b - bannerH, r, b)

        val rowH = if (!mirror) cellsH else if (rowActive) bannerH else 0f
        val rowTop = b - rowH
        bases[El.HEADER.ordinal].set(l, t, r, t + hdrH)

        val gap = 6 * d
        val cw = (r - l - gap * (cellEls.size - 1)) / cellEls.size
        for ((i, e) in cellEls.withIndex()) {
            val x = l + i * (cw + gap)
            bases[e.ordinal].set(x, b - cellsH, x + cw, b)
        }

        val rpmBottom = rowTop - (if (rowH > 0f) 8 * d else 0f)
        bases[El.RPM.ordinal].set(l, rpmBottom - rpmH, r, rpmBottom)

        val spTop = if (mirror) t else t + hdrH + 4 * d
        val spBottom = bases[El.RPM.ordinal].top - 4 * d
        val spW = min(r - l, 0.62f * w)
        val cx = (l + r) / 2f
        bases[El.SPEED.ordinal].set(cx - spW / 2f, spTop, cx + spW / 2f, spBottom)
    }

    /** Keeps the (moved, resized) element fully inside the safe area so it can never be cut off. */
    private fun clampPl(pl: Placement, base: RectF) {
        val w = width.toFloat()
        val h = height.toFloat()
        val hw = base.width() * pl.s / 2f
        val hh = base.height() * pl.s / 2f
        var nx = base.centerX() + pl.dx * w
        var ny = base.centerY() + pl.dy * h
        nx = if (hw * 2f <= safe.width()) nx.coerceIn(safe.left + hw, safe.right - hw) else safe.centerX()
        ny = if (hh * 2f <= safe.height()) ny.coerceIn(safe.top + hh, safe.bottom - hh) else safe.centerY()
        pl.dx = (nx - base.centerX()) / w
        pl.dy = (ny - base.centerY()) / h
    }

    private fun placed(e: El): Placement {
        val pl = layout.get(mirror, e)
        clampPl(pl, bases[e.ordinal])
        return pl
    }

    /** The element's current rectangle (design space, before any mirror flip). */
    private fun xr(e: El, out: RectF): RectF {
        val b = bases[e.ordinal]
        val pl = layout.get(mirror, e)
        val hw = b.width() * pl.s / 2f
        val hh = b.height() * pl.s / 2f
        val cx = b.centerX() + pl.dx * width
        val cy = b.centerY() + pl.dy * height
        out.set(cx - hw, cy - hh, cx + hw, cy + hh)
        return out
    }

    private fun pick(x: Float, y: Float): El? {
        val all = El.values()
        for (i in all.indices.reversed()) {
            val e = all[i]
            if (!isVisible(e)) continue
            xr(e, tmp)
            tmp.inset(-8 * d, -8 * d)
            if (tmp.contains(x, y)) return e
        }
        return null
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        hexLine.reset()
        hexFill.reset()
        val r = 26 * d
        val dx = r * 1.5f
        val dy = r * 1.7320508f
        var col = 0
        var x = 0f
        while (x < w + r) {
            var row = 0
            var y = if (col % 2 == 0) 0f else dy / 2f
            while (y < h + r) {
                hexPath(hexLine, x, y, r - d, 0f)
                if ((col * 7 + row * 13) % 11 == 0) hexPath(hexFill, x, y, r - d, 0f)
                y += dy
                row++
            }
            x += dx
            col++
        }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        val s = state
        val alert = s.alert()
        val note = notice
        val showNote = alert == null && note != null
        val phase = (now % 30000L) / 30000f
        val frac = if (animOn) (now % 2000L) / 2000f else 0f
        val barOn = !mirror || edit || now < barUntil
        val standby = !edit && link == LinkState.NONE && !sample
        c.drawColor(C.VOID)
        hits.clear()
        computeLayout(w, h, alert != null || showNote)

        c.save()
        if (mirror) c.scale(-1f, 1f, w / 2, h / 2)
        if (!mirror) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = d
            p.color = alpha(C.DEEP, 0x55)
            c.drawPath(hexLine, p)
            p.style = Paint.Style.FILL
            p.color = alpha(C.ORANGE, 0x14)
            c.drawPath(hexFill, p)
        }
        for (e in El.values()) {
            if (!isVisible(e)) continue
            val base = bases[e.ordinal]
            val pl = placed(e)
            c.save()
            c.translate(pl.dx * w, pl.dy * h)
            c.scale(pl.s, pl.s, base.centerX(), base.centerY())
            when (e) {
                El.HEADER -> header(c, base, frac)
                El.SPEED -> speed(c, s, base, phase, standby)
                El.RPM -> rpmBar(c, s, base)
                else -> cellPanel(c, cellFor(e, s), base)
            }
            c.restore()
        }
        if (alert != null) {
            banner(c, "\u8b66\u544a", alert.text, if (alert.level == Level.FAULT) C.RED else C.ORANGE,
                alert.level == Level.FAULT, now, frac)
        } else if (note != null) {
            banner(c, "\u901a\u77e5", "$note  (TAP TO DISMISS)", C.CYAN, false, now, frac)
        }
        c.restore()

        if (edit) editOverlay(c, w, h)
        if (barOn) bottomBar(c, w, h)
        if (showNote) {
            val nr = if (mirror) RectF(w - bannerR.right, bannerR.top, w - bannerR.left, bannerR.bottom) else RectF(bannerR)
            hits.add(Pair(nr, ID_NOTICE))
        }
    }

    private fun header(c: Canvas, r: RectF, frac: Float) {
        val l = r.left
        val rr = r.right
        val t = r.top
        val tag = RectF(l, t, l + 84 * d, t + 26 * d)
        panel(c, tag, C.ORANGE, null, 0f, 7 * d)
        txt(c, "HUD-01", tag.centerX(), t + 19 * d, 17 * d, C.VOID, Paint.Align.CENTER, ls = 0.06f)
        txt(c, "F30-318i", l + 94 * d, t + 19 * d, 14 * d, C.ORANGE, ls = 0.1f)
        txt(c, "\u30d8\u30c3\u30c9\u30a2\u30c3\u30d7\u8868\u793a", l + 176 * d, t + 19 * d, 11 * d, C.PURPLE)

        val (linkText, lc) = when {
            link == LinkState.LIVE -> Pair("LINK: LIVE", C.GREEN)
            link == LinkState.CONNECTING -> Pair("LINK: CONNECTING", C.ORANGE)
            sample -> Pair("LINK: SAMPLE DATA", C.ORANGE)
            else -> Pair("LINK: NONE", C.MUTED)
        }
        val mx = r.centerX() + 40 * d
        hexP.reset()
        hexPath(hexP, mx - 14 * d, t + 13 * d, 7 * d, 0f)
        p.style = Paint.Style.FILL
        p.color = lc
        c.drawPath(hexP, p)
        txt(c, linkText, mx, t + 19 * d, 13 * d, lc, ls = 0.08f)
        txt(c, clock.format(Date()), rr, t + 19 * d, 15 * d, C.MUTED, Paint.Align.RIGHT, ls = 0.08f)

        hazard(c, RectF(l, t + 31 * d, rr, t + 37 * d), alpha(C.ORANGE, 0xCC), 0xFF1A0F00.toInt(), frac)

        val last = lines.lastOrNull() ?: return
        val col = when (last.second) {
            Level.FAULT -> C.RED
            Level.CAUTION -> C.ORANGE
            Level.GOOD -> C.GREEN
            else -> C.MUTED
        }
        txtFit(c, last.first, l, t + 54 * d, 13 * d, col, rr - l, tf = Typeface.MONOSPACE)
    }

    private fun speed(c: Canvas, s: VehicleState, r: RectF, phase: Float, standby: Boolean) {
        val cx = r.centerX()
        val cy = r.centerY()
        if (!mirror) {
            val hr = r.height() * 0.74f
            val rot = if (animOn) phase * 60f else 0f
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * d
            hexP.reset()
            hexPath(hexP, cx, cy, hr, -rot)
            p.color = alpha(C.ORANGE, 0x70)
            c.drawPath(hexP, p)
            hexP.reset()
            hexPath(hexP, cx, cy, hr * 0.84f, rot)
            p.color = alpha(C.PURPLE, 0x80)
            c.drawPath(hexP, p)
            hexP.reset()
            hexPath(hexP, cx, cy, hr * 0.68f, -rot)
            p.color = alpha(C.DEEP, 0xA0)
            c.drawPath(hexP, p)
            brackets(c, r, 16 * d, C.PURPLE)
            txt(c, "\u901f\u5ea6 / SPEED", r.left + 10 * d, r.top + 20 * d, 12 * d, C.ORANGE, ls = 0.08f)
        }
        if (standby) {
            val rh = r.height()
            txtFit(c, "HUD-01", cx - r.width() * 0.3f, r.top + rh * 0.64f, rh * 0.66f, C.WHITE,
                r.width() * 0.6f, tf = serif, sx = 0.82f)
            txt(c, "\u5f85\u6a5f\u4e2d / STANDBY", cx - r.width() * 0.3f + 4 * d, r.top + rh * 0.82f, rh * 0.15f, C.ORANGE, ls = 0.12f)
            txt(c, "NO DATA SOURCE LINKED", cx - r.width() * 0.3f + 4 * d, r.top + rh * 0.97f, rh * 0.11f, C.MUTED, ls = 0.1f)
            return
        }
        val str = s.speedKmh?.roundToInt()?.toString() ?: "--"
        var size = r.height() * 1.15f
        val maxW = r.width() * 0.62f
        val tw0 = measure(str, size, serif, 0f, 0.8f)
        if (tw0 > maxW) size *= maxW / tw0
        val tw = measure(str, size, serif, 0f, 0.8f)
        val base = cy + size * 0.35f
        txt(c, str, cx, base, size, C.WHITE, Paint.Align.CENTER, serif, sx = 0.8f)
        txt(c, "KM/H", cx + tw / 2 + 12 * d, base, 18 * d, C.MUTED, ls = 0.08f)
    }

    private fun rpmBar(c: Canvas, s: VehicleState, r: RectF) {
        val l = r.left
        val rr = r.right
        val t = r.top
        val rpm = s.rpm
        txt(c, "\u56de\u8ee2\u6570 / ENGINE SPEED / NON-LINEAR DIAL x1000", l, t + 14 * d, 12 * d, C.MUTED, ls = 0.08f)
        txt(c, "RPM", rr, t + 20 * d, 14 * d, C.MUTED, Paint.Align.RIGHT, ls = 0.08f)
        txt(c, rpm?.let { "${it.roundToInt()}" } ?: "--", rr - 46 * d, t + 20 * d, 26 * d, C.WHITE, Paint.Align.RIGHT)
        if (rpm != null && rpm > RpmScale.DIAL_MAX) {
            txt(c, "OVER DIAL SCALE", r.centerX(), t + 14 * d, 13 * d, C.WHITE, Paint.Align.CENTER, ls = 0.08f)
        }
        val n = 48
        val gap = 2 * d
        val skew = 6 * d
        val barL = l
        val barR = rr - skew
        val top = t + 26 * d
        val bottom = top + 30 * d
        val sw = (barR - barL - gap * (n - 1)) / n
        val lit = if (rpm == null) 0 else (RpmScale.fraction(rpm) * n).roundToInt().coerceIn(0, n)
        for (i in 0 until n) {
            val x = barL + i * (sw + gap)
            segP.reset()
            segP.moveTo(x + skew, top)
            segP.lineTo(x + sw + skew, top)
            segP.lineTo(x + sw, bottom)
            segP.lineTo(x, bottom)
            segP.close()
            if (i < lit) {
                p.style = Paint.Style.FILL
                p.color = if (i == lit - 1) C.GREEN else C.PURPLE
                c.drawPath(segP, p)
            } else {
                p.style = Paint.Style.FILL
                p.color = C.SURFACE
                c.drawPath(segP, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = d
                p.color = C.LINE
                c.drawPath(segP, p)
            }
        }
        // Ticks sit at their true positions on the non-linear dial: 500 rpm minor, 1000 rpm major.
        val span = barR - barL
        var k = 0
        while (k <= 7000) {
            val x = barL + RpmScale.fraction(k.toFloat()) * span + skew / 2
            val major = k % 1000 == 0
            p.style = Paint.Style.FILL
            p.color = if (major) C.WHITE else C.MUTED
            c.drawRect(x - d / 2, bottom + 3 * d, x + d / 2, bottom + (if (major) 9 else 6) * d, p)
            if (major) txt(c, (k / 1000).toString(), x, bottom + 21 * d, 11 * d, C.MUTED, Paint.Align.CENTER)
            k += 500
        }
    }

    private fun tone(v: Float?, l: Level): Int =
        if (v == null) C.MUTED else when (l) {
            Level.FAULT -> C.RED
            Level.CAUTION -> C.ORANGE
            else -> C.GREEN
        }

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
    private fun tempCell(label: String, jp: String, s: VehicleState, key: String, v: Float?, tr: Trend): Cell {
        val value = fmt(s, key, v, "\u00b0C")
        if (v == null) return Cell(label, jp, value, C.MUTED, if (s.unsupported.contains(key)) "NOT SUPPORTED BY CAR" else "")
        return Cell(label, jp, value, C.WHITE, trendText(tr), tr.max?.let { "MAX ${it.roundToInt()}\u00b0C" } ?: "")
    }

    private fun cellFor(e: El, s: VehicleState): Cell = when (e) {
        El.CLT -> tempCell("COOLANT", "\u51b7\u5374\u6c34\u6e29", s, "clt", s.coolantC, cltTrend)
        El.OIL -> tempCell("OIL", "\u6cb9\u6e29", s, "oil", s.oilC, oilTrend)
        El.LOAD -> Cell("LOAD", "\u8ca0\u8377", fmt(s, "load", s.loadPct, "%"), if (s.loadPct == null) C.MUTED else C.WHITE)
        El.MAP -> Cell("MAP ABS", "\u5438\u6c17\u5727", fmt(s, "map", s.mapKpa, " kPa"), if (s.mapKpa == null) C.MUTED else C.CYAN)
        else -> Cell("VOLTS", "\u96fb\u5727", fmt(s, "vlt", s.volts, " V", 1), tone(s.volts, s.voltLevel()))
    }

    private fun cellPanel(c: Canvas, item: Cell, r: RectF) {
        panel(c, r, C.SURFACE, C.LINE, 1f, 9 * d)
        p.style = Paint.Style.FILL
        p.color = if (item.color == C.MUTED) C.DEEP else item.color
        c.drawRect(r.left, r.top + 9 * d, r.left + 4 * d, r.bottom, p)
        val x = r.left + 12 * d
        txt(c, item.label, x, r.top + 16 * d, 11 * d, C.MUTED, ls = 0.08f)
        txt(c, item.jp, r.right - 10 * d, r.top + 16 * d, 11 * d, C.PURPLE, Paint.Align.RIGHT)
        txtFit(c, item.value, x, r.top + 46 * d, 28 * d, item.color, r.width() - 22 * d)
        if (item.sub1.isNotEmpty()) txtFit(c, item.sub1, x, r.top + 62 * d, 11 * d, C.MUTED, r.width() - 22 * d, ls = 0.04f)
        if (item.sub2.isNotEmpty()) txtFit(c, item.sub2, x, r.top + 75 * d, 11 * d, C.MUTED, r.width() - 22 * d, ls = 0.04f)
    }

    private fun banner(c: Canvas, kanji: String, text: String, col: Int, fault: Boolean, now: Long, frac: Float) {
        val r = bannerR
        val dim = fault && animOn && (now / 500) % 2L == 1L
        val shown = if (dim) alpha(col, 0x5A) else col
        box(c, r, C.VOID, null, 0f)
        val inner = RectF(r.left, r.top + 9 * d, r.right, r.bottom - 9 * d)
        box(c, inner, if (fault) C.REDBG else C.VOID, shown, 2 * d)
        hazard(c, RectF(r.left, r.top, r.right, r.top + 9 * d), shown, C.VOID, frac)
        hazard(c, RectF(r.left, r.bottom - 9 * d, r.right, r.bottom), shown, C.VOID, 1f - frac)
        box(c, RectF(r.left, inner.top, r.left + 104 * d, inner.bottom), shown, null, 0f)
        txt(c, kanji, r.left + 52 * d, inner.centerY() + 10 * d, 30 * d, C.VOID, Paint.Align.CENTER, serif, sx = 0.85f)
        txtFit(c, text, r.left + 118 * d, inner.centerY() + 8 * d, 22 * d, shown, r.width() - 128 * d)
    }

    private fun editOverlay(c: Canvas, w: Float, h: Float) {
        for (e in El.values()) {
            if (!isVisible(e)) continue
            xr(e, tmp)
            val sr = if (mirror) RectF(w - tmp.right, tmp.top, w - tmp.left, tmp.bottom) else RectF(tmp)
            val on = e == sel
            p.style = Paint.Style.STROKE
            p.strokeWidth = (if (on) 3 else 1.5f) * d
            p.color = if (on) C.GREEN else C.ORANGE
            p.pathEffect = if (on) null else dash
            c.drawRect(sr, p)
            p.pathEffect = null
            if (on) {
                val pct = (layout.get(mirror, e).s * 100f).roundToInt()
                val ty = if (sr.top < 18 * d) sr.bottom + 14 * d else sr.top - 5 * d
                txt(c, "${e.label}  $pct%", sr.left + 2 * d, ty, 13 * d, C.GREEN, ls = 0.08f)
            }
        }
        txt(c, "LAYOUT: TAP AN ELEMENT, DRAG TO MOVE", w / 2, h - barH - 8 * d, 12 * d, C.ORANGE, Paint.Align.CENTER, ls = 0.1f)
    }

    private fun bottomBar(c: Canvas, w: Float, h: Float) {
        val top = h - barH
        box(c, RectF(0f, top, w, h), C.VOID, null, 0f)
        p.style = Paint.Style.FILL
        p.color = C.PURPLE
        c.drawRect(0f, top, w, top + d, p)
        val btns = if (edit) {
            listOf(Btn("SMALLER", false, ID_SMALLER), Btn("BIGGER", false, ID_BIGGER), Btn("RESET", false, ID_RESET),
                Btn("RESET ALL", false, ID_RESET_ALL), Btn("DONE", true, ID_DONE))
        } else {
            listOf(Btn(if (link == LinkState.NONE) "LINK" else "UNLINK", link == LinkState.LIVE, ID_LINK),
                Btn("SAMPLE", sample, ID_SAMPLE), Btn("MIRROR", mirror, ID_MIRROR), Btn("LAYOUT", false, ID_LAYOUT))
        }
        val bw = w / btns.size
        for ((i, b) in btns.withIndex()) {
            val r = RectF(i * bw, top, (i + 1) * bw, h)
            hits.add(Pair(r, b.id))
            txt(c, b.label, r.centerX(), r.centerY() + 6 * d, 16 * d, if (b.on) C.GREEN else C.WHITE, Paint.Align.CENTER, ls = 0.1f)
            p.style = Paint.Style.FILL
            if (b.on) {
                p.color = C.GREEN
                c.drawRect(r.left, h - 3 * d, r.right, h, p)
            }
            if (i > 0) {
                p.color = C.LINE
                c.drawRect(r.left, top, r.left + d, h, p)
            }
        }
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x
                ly = e.y
                dragging = false
                if (edit && e.y < h - barH) {
                    val hit = pick(if (mirror) w - e.x else e.x, e.y)
                    if (hit != null) {
                        sel = hit
                        dragging = true
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val s = sel
                if (edit && dragging && s != null) {
                    val ddx = (e.x - lx) * (if (mirror) -1f else 1f)
                    val ddy = e.y - ly
                    lx = e.x
                    ly = e.y
                    val pl = layout.get(mirror, s)
                    pl.dx += ddx / w
                    pl.dy += ddy / h
                    clampPl(pl, bases[s.ordinal])
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    layout.save(mirror)
                } else {
                    val hit = hits.firstOrNull { it.first.contains(e.x, e.y) }
                    when (hit?.second) {
                        ID_LINK -> onLink?.invoke()
                        ID_SAMPLE -> toggleSample()
                        ID_MIRROR -> toggleMirror()
                        ID_LAYOUT -> toggleEdit()
                        ID_SMALLER -> adjust(0.9f)
                        ID_BIGGER -> adjust(1.1f)
                        ID_RESET -> resetSel()
                        ID_RESET_ALL -> {
                            layout.resetAll(mirror)
                            sel = null
                            log("Layout reset")
                        }
                        ID_DONE -> toggleEdit()
                        ID_NOTICE -> notice = null
                    }
                }
                if (mirror && !edit) barUntil = SystemClock.uptimeMillis() + 4000
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
