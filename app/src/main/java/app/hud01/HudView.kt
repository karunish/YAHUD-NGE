package app.hud01

import android.content.Context
import android.graphics.Canvas
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class LinkState { NONE, CONNECTING, LIVE }

/** Colour tokens from the NERV UI design system (TheGreatGildo/nerv-ui, MIT). */
private object K {
    val VOID = 0xFF000000.toInt()
    val PANEL = 0xFF0C0C0A.toInt()
    val ORANGE = 0xFFFF9830.toInt()
    val HOT = 0xFFFFCC50.toInt()
    val GREEN = 0xFF50FF50.toInt()
    val CYAN = 0xFF20F0FF.toInt()
    val RED = 0xFFFF3030.toInt()
    val STEEL = 0xFFD8D8D0.toInt()
    val DIM = 0xFF888880.toInt()
    val LINE = 0x38C8C8C0
}

/** Heavy compressed serif for numerals, condensed stamp face for labels, mono for data. Falls back to system fonts. */
private class Fonts(private val ctx: Context) {
    private fun load(path: String, variation: String?, fallback: Typeface): Typeface = try {
        val b = Typeface.Builder(ctx.assets, path)
        if (variation != null) b.setFontVariationSettings(variation)
        b.build() ?: fallback
    } catch (e: Exception) {
        fallback
    }

    private val notoSerif = load("fonts/NotoSerifDisplay.ttf", "'wght' 900, 'wdth' 80", Typeface.create("serif", Typeface.BOLD))
    /** Uses MatissePro-EB.otf if you add your own licensed copy to assets/fonts/; otherwise Noto Serif Display. */
    val title = load("fonts/MatissePro-EB.otf", null, notoSerif)
    val mono = load("fonts/JetBrainsMono.ttf", "'wght' 700", Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
    val stamp = load("fonts/SairaExtraCondensed-ExtraBold.ttf", null, Typeface.create("sans-serif-condensed", Typeface.BOLD))
}

/** The whole HUD on one canvas. Mirror mode flips it horizontally for windshield reflection. */
class HudView(context: Context) : View(context) {
    var state = VehicleState()
        set(v) {
            field = v
            val n = SystemClock.uptimeMillis()
            cltTrend.add(n, v.coolantC)
            oilTrend.add(n, v.oilC)
            if (sample) {
                gearSample.update(v.speedKmh, v.rpm)
                fuelSample.update(n, v.speedKmh, v.fuelPct)
            } else if (link == LinkState.LIVE) {
                gearLive.update(v.speedKmh, v.rpm)
                fuelLive.update(n, v.speedKmh, v.fuelPct)
                engineEvents(n, v.rpm)
            }
        }
    var sample = true
    var mirror = false
    var link = LinkState.NONE
        set(v) {
            val old = field
            field = v
            resetTrends()
            if (v == LinkState.CONNECTING) {
                eng.reset()
                engineWasOn = false
                reminderUntil = 0L
            }
            if (old == LinkState.LIVE && v == LinkState.NONE) {
                if (!userUnlink && engineWasOn) remind()
                userUnlink = false
            }
        }
    /** Set by the activity just before it unlinks on purpose, so that no "remove the adapter" reminder is raised. */
    var userUnlink = false
    var onLink: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val f = Fonts(context)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val lines = ArrayList<Pair<String, Level>>()
    private val hits = ArrayList<Pair<RectF, Int>>()
    val gearLive = GearEstimator()
    private val gearSample = GearEstimator()
    private val eng = EngineEvents()
    private var engineWasOn = false
    private var reminderUntil = 0L
    val fuelLive = FuelModel()
    private val fuelSample = FuelModel()
    val pages = TilePages()
    private val tileRects = ArrayList<RectF>()
    private val cltTrend = Trend()
    private val oilTrend = Trend()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val animOn = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    private val t0 = SystemClock.uptimeMillis()
    private val emblemId = context.resources.getIdentifier("emblem", "drawable", context.packageName)
    private var barUntil = 0L
    private var emergency = false

    private fun gearNow(): Int? = if (sample) gearSample.gear else gearLive.gear

    private fun gearRatioNow(): Float? = if (sample) gearSample.ratio else gearLive.ratio

    private fun fuelNow(): FuelModel = if (sample) fuelSample else fuelLive

    private fun engineEvents(now: Long, rpm: Float?) {
        val ev = eng.update(now, rpm)
        if (ev == EngineEvents.Ev.STARTED) {
            engineWasOn = true
            log("Engine running. Ready to move.")
        } else if (ev == EngineEvents.Ev.STOPPED) {
            engineWasOn = false
            remind()
        }
    }

    private fun remind() {
        reminderUntil = SystemClock.uptimeMillis() + 120_000L
        log("Engine off. Remove the OBD adapter from the car.", Level.CAUTION)
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

    // ---- drawing helpers -------------------------------------------------------------------

    private fun tx(
        c: Canvas, s: String, x: Float, y: Float, size: Float, col: Int, tf: Typeface,
        a: Paint.Align = Paint.Align.LEFT, ls: Float = 0f, sx: Float = 1f
    ) {
        p.style = Paint.Style.FILL
        p.typeface = tf
        p.textSize = size
        p.color = col
        p.textAlign = a
        p.letterSpacing = ls
        p.textScaleX = sx
        c.drawText(s, x, y, p)
        p.textScaleX = 1f
    }

    private fun mw(s: String, size: Float, tf: Typeface, ls: Float = 0f): Float {
        p.typeface = tf
        p.textSize = size
        p.letterSpacing = ls
        p.textScaleX = 1f
        return p.measureText(s)
    }

    private fun fit(s: String, maxW: Float, size: Float, tf: Typeface): String {
        p.typeface = tf
        p.textSize = size
        p.letterSpacing = 0f
        p.textScaleX = 1f
        val n = p.breakText(s, true, maxW, null)
        return if (n >= s.length) s else s.substring(0, max(0, n - 1)) + "\u2026"
    }

    private fun fill(c: Canvas, l: Float, tp: Float, r: Float, b: Float, col: Int) {
        p.style = Paint.Style.FILL
        p.color = col
        c.drawRect(l, tp, r, b, p)
    }

    private fun frame(c: Canvas, r: RectF, col: Int, wd: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = wd * d
        p.color = col
        c.drawRect(r, p)
    }

    /** Panel with the signature orange title bar and a Japanese sub-label. */
    private fun panel(c: Canvas, r: RectF, title: String, jp: String) {
        fill(c, r.left, r.top, r.right, r.bottom, K.PANEL)
        frame(c, r, K.LINE, 1f)
        fill(c, r.left, r.top, r.right, r.top + 18 * d, K.ORANGE)
        tx(c, title, r.left + 6 * d, r.top + 14 * d, 14 * d, K.VOID, f.stamp, ls = 0.1f)
        tx(c, jp, r.right - 6 * d, r.top + 13 * d, 10 * d, K.VOID, f.mono, Paint.Align.RIGHT)
    }

    private fun brackets(c: Canvas, r: RectF, col: Int) {
        val len = 22 * d
        val wd = 2.5f * d
        fill(c, r.left, r.top, r.left + len, r.top + wd, col)
        fill(c, r.left, r.top, r.left + wd, r.top + len, col)
        fill(c, r.right - len, r.top, r.right, r.top + wd, col)
        fill(c, r.right - wd, r.top, r.right, r.top + len, col)
        fill(c, r.left, r.bottom - wd, r.left + len, r.bottom, col)
        fill(c, r.left, r.bottom - len, r.left + wd, r.bottom, col)
        fill(c, r.right - len, r.bottom - wd, r.right, r.bottom, col)
        fill(c, r.right - wd, r.bottom - len, r.right, r.bottom, col)
    }

    private fun hazard(c: Canvas, r: RectF, col: Int) {
        c.save()
        c.clipRect(r)
        val step = 10 * d
        var x = r.left - r.height()
        p.style = Paint.Style.FILL
        p.color = col
        while (x < r.right) {
            path.reset()
            path.moveTo(x, r.bottom)
            path.lineTo(x + step, r.bottom)
            path.lineTo(x + step + r.height(), r.top)
            path.lineTo(x + r.height(), r.top)
            path.close()
            c.drawPath(path, p)
            x += step * 2
        }
        c.restore()
    }

    private fun segBar(c: Canvas, r: RectF, n: Int, lit: Int, col: Int) {
        val gap = 2 * d
        val sw = (r.width() - gap * (n - 1)) / n
        for (i in 0 until n) {
            val x = r.left + i * (sw + gap)
            val sr = RectF(x, r.top, x + sw, r.bottom)
            if (i < lit) fill(c, sr.left, sr.top, sr.right, sr.bottom, col) else frame(c, sr, K.LINE, 1f)
        }
    }

    /** Boot reveal: panels cut in one after another with a short flicker. Skipped when animations are off. */
    private fun shown(i: Int, now: Long): Boolean {
        if (!animOn) return true
        val e = now - t0 - i * 150L
        return e >= 0 && (e >= 130 || (e / 45) % 2L == 0L)
    }

    // ---- layout ----------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        val s = state
        val alert = s.alert()
            ?: (if (now < reminderUntil) Alert(Level.NOMINAL, "ENGINE OFF. REMOVE THE OBD ADAPTER FROM THE CAR. TAP TO DISMISS.") else null)
        emergency = alert?.level == Level.FAULT
        val cut = rootWindowInsets?.displayCutout
        val sl = (cut?.safeInsetLeft ?: 0).toFloat()
        val sr = (cut?.safeInsetRight ?: 0).toFloat()
        val pad = 14 * d
        val l0 = pad + (if (mirror) sr else sl)
        val r0 = w - pad - (if (mirror) sl else sr)
        val barH = 52 * d
        c.drawColor(K.VOID)
        hits.clear()
        tileRects.clear()

        c.save()
        if (mirror) c.scale(-1f, 1f, w / 2, h / 2)
        var y = if (mirror) pad else 8 * d
        if (!mirror) {
            header(c, l0, r0, w, y, now)
            y += 36 * d
        }
        if (alert != null) {
            banner(c, alert, l0, y, r0, y + 50 * d, now)
            y += 56 * d
        }
        val bottom = h - pad - (if (mirror) 0f else barH)
        val rpmTop = bottom - 96 * d
        val mainBottom = rpmTop - 6 * d
        val gap = 8 * d
        val cw = r0 - l0 - 2 * gap
        if (mirror && !emergency) {
            mirrorTiles(c, s, l0, r0, y, mainBottom, gap)
        } else if (mirror || emergency) {
            centerPanel(c, RectF(w * 0.16f, y, w * 0.84f, mainBottom), s)
        } else {
            val lr = RectF(l0, y, l0 + cw * 0.28f, mainBottom)
            val cr = RectF(lr.right + gap, y, lr.right + gap + cw * 0.38f, mainBottom)
            val rr = RectF(cr.right + gap, y, r0, mainBottom)
            if (shown(1, now)) leftPanel(c, lr, s)
            if (shown(2, now)) centerPanel(c, cr, s)
            if (shown(3, now)) rightColumn(c, rr, s)
        }
        if (shown(4, now)) rpmBlock(c, s, l0, r0, rpmTop)
        c.restore()

        if (!mirror || now < barUntil) bottomBar(c, w, h, barH)
    }

    private fun header(c: Canvas, l: Float, r: Float, w: Float, y: Float, now: Long) {
        if (!shown(0, now)) return
        val name = "UNIT F30-318i / HUD-01"
        tx(c, name, l, y + 20 * d, 16 * d, K.ORANGE, f.stamp, ls = 0.12f)
        tx(c, "\u8eca\u8f09\u8868\u793a", l + mw(name, 16 * d, f.stamp, 0.12f) + 10 * d, y + 19 * d, 10 * d, K.DIM, f.mono)
        val (label, jp, col) = when {
            link == LinkState.LIVE -> Triple("LINK: LIVE", "\u7a3c\u50cd\u4e2d", K.GREEN)
            link == LinkState.CONNECTING -> Triple("LINK: CONNECTING", "\u63a5\u7d9a\u4e2d", K.ORANGE)
            sample -> Triple("LINK: SAMPLE DATA", "\u6a21\u64ec\u30c7\u30fc\u30bf", K.ORANGE)
            else -> Triple("LINK: NONE", "\u5f85\u6a5f", K.DIM)
        }
        val cx = w / 2 - 40 * d
        p.style = Paint.Style.FILL
        p.color = col
        c.drawCircle(cx, y + 14 * d, 4 * d, p)
        tx(c, label, cx + 10 * d, y + 20 * d, 15 * d, col, f.stamp, ls = 0.1f)
        tx(c, jp, cx + 18 * d + mw(label, 15 * d, f.stamp, 0.1f), y + 19 * d, 10 * d, K.DIM, f.mono)
        tx(c, clock.format(Date()), r, y + 20 * d, 13 * d, K.DIM, f.mono, Paint.Align.RIGHT)
        fill(c, l, y + 28 * d, r, y + 29 * d, K.LINE)
        fill(c, l, y + 28 * d, l + 90 * d, y + 30 * d, K.ORANGE)
    }

    private class Row(val label: String, val jp: String, val value: String, val color: Int, val sub1: String = "", val sub2: String = "")

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
    private fun tempRow(label: String, jp: String, s: VehicleState, key: String, v: Float?, tr: Trend): Row {
        val value = fmt(s, key, v, "\u00b0C")
        if (v == null) return Row(label, jp, value, K.DIM, if (s.unsupported.contains(key)) "NOT SUPPORTED BY CAR" else "")
        return Row(label, jp, value, K.STEEL, trendText(tr), tr.max?.let { "MAX ${it.roundToInt()}\u00b0C" } ?: "")
    }

    private fun leftPanel(c: Canvas, r: RectF, s: VehicleState) {
        panel(c, r, "SYSTEM STATUS", "\u7cfb\u7d71\u72b6\u614b")
        val rows = listOf(
            tempRow("COOLANT", "\u51b7\u5374\u6c34\u6e29", s, "clt", s.coolantC, cltTrend),
            tempRow("OIL", "\u6cb9\u6e29", s, "oil", s.oilC, oilTrend),
            Row("MAP ABS", "\u5438\u6c17\u5727", fmt(s, "map", s.mapKpa, " kPa"), if (s.mapKpa == null) K.DIM else K.STEEL),
            Row("VOLTS", "\u96fb\u5727", fmt(s, "vlt", s.volts, " V", 1), if (s.voltLevel() == Level.CAUTION) K.ORANGE else K.STEEL),
        )
        val top = r.top + 22 * d
        val rh = (r.bottom - top - 2 * d) / rows.size
        for ((i, row) in rows.withIndex()) {
            val y = top + i * rh
            if (i > 0) fill(c, r.left + 6 * d, y, r.right - 6 * d, y + d, K.LINE)
            tx(c, row.label, r.left + 8 * d, y + 14 * d, 12 * d, K.ORANGE, f.stamp, ls = 0.1f)
            tx(c, row.jp, r.left + 14 * d + mw(row.label, 12 * d, f.stamp, 0.1f), y + 13 * d, 9 * d, K.DIM, f.mono)
            tx(c, row.value, r.right - 8 * d, y + 17 * d, 18 * d, row.color, f.mono, Paint.Align.RIGHT)
            if (rh > 32 * d) {
                tx(c, row.sub1, r.left + 8 * d, y + 30 * d, 9 * d, K.DIM, f.mono)
                tx(c, row.sub2, r.right - 8 * d, y + 30 * d, 9 * d, K.DIM, f.mono, Paint.Align.RIGHT)
            }
        }
    }

    private fun centerPanel(c: Canvas, r: RectF, s: VehicleState, showGear: Boolean = true) {
        fill(c, r.left, r.top, r.right, r.bottom, K.PANEL)
        val col = if (emergency) K.RED else K.ORANGE
        brackets(c, r, col)
        tx(c, "SPEED", r.left + 14 * d, r.top + 20 * d, 13 * d, K.ORANGE, f.stamp, ls = 0.15f)
        tx(c, "\u901f\u5ea6", r.left + 22 * d + mw("SPEED", 13 * d, f.stamp, 0.15f), r.top + 19 * d, 10 * d, K.DIM, f.mono)
        if (!sample && link == LinkState.NONE) {
            // \u51fa\u6483\u5f85\u547d = "sortie standby", shown while no data source is linked
            val big = min(r.height() * 0.34f, r.width() * 0.19f)
            tx(c, "\u51fa\u6483\u5f85\u547d", r.centerX(), r.top + r.height() * 0.55f, big, col, f.title, Paint.Align.CENTER)
            tx(c, "SORTIE STANDBY", r.centerX(), r.bottom - 12 * d, 14 * d, K.DIM, f.stamp, Paint.Align.CENTER, ls = 0.25f)
            return
        }
        val str = s.speedKmh?.roundToInt()?.toString() ?: "--"
        var size = r.height() * 0.8f
        p.typeface = f.title
        p.textSize = size
        p.letterSpacing = 0f
        p.textScaleX = 0.9f
        val tw = p.measureText(str)
        val maxW = r.width() * 0.8f
        if (tw > maxW) size *= maxW / tw
        tx(c, str, r.centerX(), r.top + r.height() * 0.5f + size * 0.34f, size, col, f.title, Paint.Align.CENTER, sx = 0.9f)
        tx(c, "KM/H", r.centerX(), r.bottom - 12 * d, 14 * d, K.DIM, f.stamp, Paint.Align.CENTER, ls = 0.25f)
        // Gear is an estimate learned from rpm / speed (the gearbox computer is not read).
        if (!showGear) return
        val gx = r.right - 16 * d
        tx(c, "GEAR EST", gx, r.bottom - 38 * d, 10 * d, K.DIM, f.stamp, Paint.Align.RIGHT, ls = 0.15f)
        tx(c, gearNow()?.toString() ?: "-", gx, r.bottom - 12 * d, 30 * d, col, f.title, Paint.Align.RIGHT)
    }

    private fun rightColumn(c: Canvas, r: RectF, s: VehicleState) {
        val g = 4 * d
        val e = RectF(r.left, r.top, r.right, r.top + r.height() * 0.24f)
        val ld = RectF(r.left, e.bottom + g, r.right, e.bottom + g + r.height() * 0.30f)
        val lg = RectF(r.left, ld.bottom + g, r.right, r.bottom)
        emblem(c, e)
        panel(c, ld, "ENGINE LOAD", "\u8ca0\u8377")
        val loadCol = if (s.loadPct == null) K.DIM else K.STEEL
        val lit = ((s.loadPct ?: 0f) / 100f * 20f).roundToInt().coerceIn(0, 20)
        val mid = ld.top + 18 * d + (ld.height() - 18 * d) / 2
        // value and bar sit side by side so they can never overlap, however short the panel gets
        tx(c, fmt(s, "load", s.loadPct, "%"), ld.left + 8 * d, mid + 7 * d, 20 * d, loadCol, f.mono)
        segBar(c, RectF(ld.left + 70 * d, mid - 5 * d, ld.right - 8 * d, mid + 5 * d), 20, lit, K.ORANGE)
        panel(c, lg, "EVENT LOG", "\u8a18\u9332")
        val top = lg.top + 22 * d
        val lh = 12 * d
        val n = ((lg.bottom - top - 2 * d) / lh).toInt()
        for ((i, ln) in lines.takeLast(max(0, n)).withIndex()) {
            val col = when (ln.second) {
                Level.FAULT -> K.RED
                Level.CAUTION -> K.ORANGE
                else -> K.DIM
            }
            tx(c, fit(ln.first, lg.width() - 12 * d, 9 * d, f.mono), lg.left + 6 * d, top + (i + 1) * lh - 2 * d, 9 * d, col, f.mono)
        }
    }

    /** Optional emblem: drop your own `emblem` drawable into res/drawable to replace the placeholder mark. */
    private fun emblem(c: Canvas, e: RectF) {
        fill(c, e.left, e.top, e.right, e.bottom, K.PANEL)
        frame(c, e, K.LINE, 1f)
        val rad = e.height() * 0.36f
        val cx = e.left + 10 * d + rad
        if (emblemId != 0) {
            val dr = resources.getDrawable(emblemId, null)
            dr.setBounds((cx - rad).toInt(), (e.centerY() - rad).toInt(), (cx + rad).toInt(), (e.centerY() + rad).toInt())
            dr.draw(c)
        } else {
            path.reset()
            for (i in 0 until 6) {
                val a = Math.PI / 3 * i + Math.PI / 6
                val x = cx + rad * cos(a).toFloat()
                val y = e.centerY() + rad * sin(a).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = K.ORANGE
            c.drawPath(path, p)
            tx(c, "01", cx, e.centerY() + 6 * d, 16 * d, K.ORANGE, f.stamp, Paint.Align.CENTER)
        }
        val x = cx + rad + 12 * d
        tx(c, "HUD-01", x, e.centerY() + 2 * d, 22 * d, K.ORANGE, f.stamp, ls = 0.1f)
        tx(c, "\u8eca\u8f09\u8868\u793a", x, e.centerY() + 16 * d, 10 * d, K.DIM, f.mono)
    }

    private fun rpmBlock(c: Canvas, s: VehicleState, l: Float, r: Float, top: Float) {
        val rpm = s.rpm
        tx(c, "ENGINE SPEED", l, top + 18 * d, 13 * d, K.ORANGE, f.stamp, ls = 0.15f)
        tx(c, "\u56de\u8ee2\u6570", l + 8 * d + mw("ENGINE SPEED", 13 * d, f.stamp, 0.15f), top + 17 * d, 10 * d, K.DIM, f.mono)
        tx(c, rpm?.roundToInt()?.toString() ?: "--", r - 48 * d, top + 30 * d, 36 * d, K.STEEL, f.stamp, Paint.Align.RIGHT)
        tx(c, "RPM", r, top + 30 * d, 14 * d, K.DIM, f.stamp, Paint.Align.RIGHT, ls = 0.15f)
        if (rpm != null && rpm > RpmScale.DIAL_MAX) {
            tx(c, "OVER DIAL SCALE", (l + r) / 2, top + 32 * d, 14 * d, K.HOT, f.stamp, Paint.Align.CENTER, ls = 0.15f)
        }
        val bt = top + 36 * d
        val bb = bt + 28 * d
        val n = 60
        val lit = if (rpm == null) 0 else (RpmScale.fraction(rpm) * n).roundToInt().coerceIn(0, n)
        segBar(c, RectF(l, bt, r, bb), n, lit, K.ORANGE)
        // Ticks sit at their true positions on the non-linear dial: 500 rpm minor, 1,000 rpm major.
        var k = 0
        while (k <= 7000) {
            val x = l + RpmScale.fraction(k.toFloat()) * (r - l)
            val major = k % 1000 == 0
            fill(c, x - d / 2, bb + 2 * d, x + d / 2, bb + (if (major) 8 else 5) * d, if (major) K.STEEL else K.DIM)
            if (major) tx(c, (k / 1000).toString(), x, bb + 20 * d, 11 * d, K.DIM, f.mono, Paint.Align.CENTER)
            k += 500
        }
    }

    private fun banner(c: Canvas, a: Alert, l: Float, top: Float, r: Float, b: Float, now: Long) {
        val fault = a.level == Level.FAULT
        val base = if (fault) K.RED else K.ORANGE
        val col = if (fault && animOn && (now / 500) % 2L == 1L) (base and 0x00FFFFFF) or (0x70 shl 24) else base
        fill(c, l, top, r, b, if (fault) 0xFF1A0202.toInt() else K.PANEL)
        hazard(c, RectF(l, top, l + 40 * d, b), col)
        hazard(c, RectF(r - 40 * d, top, r, b), col)
        frame(c, RectF(l, top, r, b), col, 2f)
        val sx = l + 52 * d
        val sw = if (fault) 96 * d else 70 * d
        fill(c, sx, top + 8 * d, sx + sw, b - 8 * d, col)
        // fault: \u975e\u5e38\u4e8b\u614b = "state of emergency"; caution: \u8b66\u544a = "warning"
        // reminder (not a fault): \u901a\u77e5 = "notice"
        val stamp = if (fault) "\u975e\u5e38\u4e8b\u614b" else if (a.level == Level.NOMINAL) "\u901a\u77e5" else "\u8b66\u544a"
        tx(c, stamp, sx + sw / 2, (top + b) / 2 + 8 * d, if (fault) 21 * d else 26 * d, K.VOID, f.title, Paint.Align.CENTER)
        val room = r - 48 * d - (sx + sw + 12 * d)
        tx(c, fit(a.text, room, 20 * d, f.stamp), sx + sw + 12 * d, (top + b) / 2 + 7 * d, 20 * d, col, f.stamp, ls = 0.04f)
    }

    private fun bottomBar(c: Canvas, w: Float, h: Float, bh: Float) {
        val top = h - bh
        fill(c, 0f, top, w, h, K.VOID)
        fill(c, 0f, top, w, top + d, K.ORANGE)
        val names = listOf(if (link == LinkState.NONE) "LINK" else "UNLINK", "SAMPLE", "MIRROR")
        val on = listOf(link == LinkState.LIVE, sample, mirror)
        val bw = w / names.size
        for (i in names.indices) {
            val rc = RectF(i * bw, top, (i + 1) * bw, h)
            hits.add(Pair(rc, i))
            val col = if (on[i]) K.GREEN else K.STEEL
            tx(c, names[i], rc.centerX(), rc.centerY() + 6 * d, 17 * d, col, f.stamp, Paint.Align.CENTER, ls = 0.2f)
            if (on[i]) fill(c, rc.left, h - 3 * d, rc.right, h, K.GREEN)
            if (i > 0) fill(c, rc.left, top, rc.left + d, h, K.LINE)
        }
    }

    // ---- mirror tiles ------------------------------------------------------------------------

    private class TileData(val value: String, val unit: String, val color: Int, val sub1: String = "", val sub2: String = "")

    private fun num(s: VehicleState, key: String, v: Float?, dec: Int, unit: String, col: Int, sub1: String = "", sub2: String = ""): TileData {
        if (v == null) {
            val na = s.unsupported.contains(key)
            return TileData(if (na) "N/A" else "--", "", K.DIM, if (na) "NOT SUPPORTED BY CAR" else "")
        }
        val text = if (dec == 0) v.roundToInt().toString() else String.format(Locale.US, "%.${dec}f", v)
        return TileData(text, unit, col, sub1, sub2)
    }

    private fun tileData(t: TileType, s: VehicleState): TileData = when (t) {
        TileType.GEAR -> {
            val g = gearNow()
            val k = gearRatioNow()
            TileData(
                g?.toString() ?: "-", "", if (g == null) K.DIM else K.ORANGE, "ESTIMATED FROM RPM / SPEED",
                if (k == null) "" else "RATIO " + String.format(Locale.US, "%.1f", k) + " RPM PER KM/H"
            )
        }
        TileType.FUEL -> {
            val fm = fuelNow()
            val l = fm.litres()
            val km = fm.rangeKm()
            if (l == null) {
                num(s, "fuel", null, 0, "", K.DIM)
            } else {
                TileData(
                    String.format(Locale.US, "%.1f", l), "L", K.STEEL,
                    if (km != null) "RANGE ~${km.roundToInt()} KM" else "RANGE LEARNING",
                    "${(fm.pct ?: 0f).roundToInt()}% OF ${Car.TANK_L.roundToInt()} L TANK"
                )
            }
        }
        TileType.COOLANT -> num(s, "clt", s.coolantC, 0, "\u00b0C", K.STEEL, trendText(cltTrend), cltTrend.max?.let { "MAX ${it.roundToInt()}\u00b0C" } ?: "")
        TileType.OIL -> num(s, "oil", s.oilC, 0, "\u00b0C", K.STEEL, trendText(oilTrend), oilTrend.max?.let { "MAX ${it.roundToInt()}\u00b0C" } ?: "")
        TileType.LOAD -> num(s, "load", s.loadPct, 0, "%", K.STEEL)
        TileType.MAP -> num(s, "map", s.mapKpa, 0, "kPa", K.STEEL)
        TileType.VOLTS -> num(s, "vlt", s.volts, 1, "V", if (s.voltLevel() == Level.CAUTION) K.ORANGE else K.STEEL)
    }

    private fun drawTile(c: Canvas, r: RectF, type: TileType, s: VehicleState) {
        fill(c, r.left, r.top, r.right, r.bottom, K.PANEL)
        brackets(c, r, K.ORANGE)
        tx(c, type.label, r.left + 14 * d, r.top + 22 * d, 12 * d, K.ORANGE, f.stamp, ls = 0.15f)
        tx(c, type.jp, r.left + 22 * d + mw(type.label, 12 * d, f.stamp, 0.15f), r.top + 21 * d, 9 * d, K.DIM, f.mono)
        val t = tileData(type, s)
        val lines = if (r.height() >= 100 * d) 2 else if (r.height() >= 70 * d) 1 else 0
        val subs = listOf(t.sub1, t.sub2).filter { it.isNotEmpty() }.take(lines)
        val top = r.top + 30 * d
        val bot = r.bottom - 10 * d - subs.size * 12 * d
        val ah = max(14 * d, bot - top)
        val unitW = if (t.unit.isEmpty()) 0f else mw(t.unit, 16 * d, f.stamp, 0.1f) + 8 * d
        var size = ah * 1.1f
        p.typeface = f.title
        p.textSize = size
        p.letterSpacing = 0f
        p.textScaleX = 0.9f
        val tw = p.measureText(t.value)
        val maxW = r.width() - 32 * d - unitW
        if (tw > maxW) size *= maxW / tw
        p.textSize = size
        val vw = p.measureText(t.value)
        p.textScaleX = 1f
        val x0 = r.centerX() - (vw + unitW) / 2
        val base = top + (ah + size * 0.7f) / 2
        tx(c, t.value, x0, base, size, t.color, f.title, sx = 0.9f)
        if (t.unit.isNotEmpty()) tx(c, t.unit, x0 + vw + 8 * d, base, 16 * d, K.DIM, f.stamp, ls = 0.1f)
        var y = r.bottom - 10 * d
        for (line in subs.reversed()) {
            tx(c, fit(line, r.width() - 20 * d, 9 * d, f.mono), r.centerX(), y, 9 * d, K.DIM, f.mono, Paint.Align.CENTER)
            y -= 12 * d
        }
    }

    /** Mirror layout: speed fixed on the left, the current page of tiles on the right. */
    private fun mirrorTiles(c: Canvas, s: VehicleState, l: Float, r: Float, top: Float, bottom: Float, gap: Float) {
        val sp = RectF(l, top, l + (r - l) * 0.46f, bottom)
        centerPanel(c, sp, s, false)
        val page = pages.pages[pages.index]
        val area = RectF(sp.right + gap, top, r, bottom)
        val bigW = if (page.size == 1) area.width() else area.width() * 0.58f
        val big = RectF(area.left, area.top, area.left + bigW, area.bottom)
        drawTile(c, big, page[0], s)
        tileRects.add(big)
        val n = page.size - 1
        if (n > 0) {
            val th = (area.height() - gap * (n - 1)) / n
            for (i in 1..n) {
                val y = area.top + (i - 1) * (th + gap)
                val rc = RectF(big.right + gap, y, area.right, y + th)
                drawTile(c, rc, page[i], s)
                tileRects.add(rc)
            }
        }
        for (i in pages.pages.indices) {
            val x = area.centerX() + (i - (pages.pages.size - 1) / 2f) * 10 * d
            fill(c, x - 3 * d, bottom + d, x + 3 * d, bottom + 5 * d, if (i == pages.index) K.ORANGE else K.LINE)
        }
    }

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) {
            downX = e.x
            downY = e.y
        } else if (e.action == MotionEvent.ACTION_UP) {
            val now = SystemClock.uptimeMillis()
            if (now < reminderUntil) reminderUntil = 0L
            val dx = e.x - downX
            val dy = e.y - downY
            val tiles = mirror && !emergency
            val hit = hits.firstOrNull { it.first.contains(e.x, e.y) }
            if (hit != null) {
                when (hit.second) {
                    0 -> onLink?.invoke()
                    1 -> toggleSample()
                    2 -> mirror = !mirror
                }
            } else if (tiles && abs(dx) > 70 * d && abs(dx) > 2 * abs(dy)) {
                pages.turn(if (dx < 0f) 1 else -1) // swipe on the phone screen: left = next page
            } else if (tiles && abs(dx) < 24 * d && abs(dy) < 24 * d) {
                val slot = tileRects.indexOfFirst { it.contains(width - e.x, e.y) } // the canvas is mirrored
                if (slot >= 0) pages.swap(slot)
            }
            if (mirror) barUntil = now + 4000
            invalidate()
        }
        return true
    }
}
