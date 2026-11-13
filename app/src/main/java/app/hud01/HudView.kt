package app.hud01

import android.content.Context
import android.graphics.Bitmap
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

/**
 * The screens, one per look of the iDrive 7 EVA HUD: Sport is the orange battle display, Command adds the MAGI
 * sortie check, Eco Pro is the blue economy display, Comfort is the calm peach clock. The MODE button cycles them.
 */
enum class Mode(val label: String, val jp: String) {
    SPORT("SPORT", "\u6226\u95d8"),
    COMMAND("COMMAND", "\u6307\u4ee4\u5ba4"),
    ECO("ECO PRO", "\u7bc0\u96fb"),
    COMFORT("COMFORT", "\u5e73\u6642"),
}

/** Colour tokens that do not change with the mode. */
private object K {
    val VOID = 0xFF000000.toInt()
    val PANEL = 0xFF0C0C0A.toInt()
    val GREEN = 0xFF50FF50.toInt()
    val RED = 0xFFFF3030.toInt()
    val ALERT = 0xFFFF1100.toInt()
    val STEEL = 0xFFD8D8D0.toInt()
    val WHITE = 0xFFFFFFFF.toInt()
    val DIM = 0xFF888880.toInt()
    val LINE = 0x38C8C8C0
    val HOT = 0xFFFFCC50.toInt()
    // MAGI vote colours, as in the reference
    val OK = 0xFF54E08F.toInt()
    val OK_SOFT = 0xFF9FD9B8.toInt()
    val WAIT = 0xFFFFB000.toInt()
    val WAIT_SOFT = 0xFFFFD98A.toInt()
}

/** One mode's colours. fg lit parts, tag labels, sub small captions, faint unlit segments, wire rules and frames. */
private class Pal(val fg: Int, val tag: Int, val sub: Int, val faint: Int, val wire: Int, val mid: Int, val ramp: Boolean)

// Values are the reference's theme tokens (shared/hud_themes.js: eva, eva_calm, eva_eco).
private val PAL_SPORT = Pal(0xFFFF8800.toInt(), 0xFFFFB45C.toInt(), 0xFFC08A48.toInt(), 0xFF3A2410.toInt(), 0xFF7A4A18.toInt(), 0xFFFFDD00.toInt(), true)
private val PAL_CALM = Pal(0xFFFFB07A.toInt(), 0xFFFFB07A.toInt(), 0xA5FFB07A.toInt(), 0x2AFFB07A, 0x5AFFB07A, 0xFFFFB07A.toInt(), false)
private val PAL_ECO = Pal(0xFF78CDFF.toInt(), 0xEB78CDFF.toInt(), 0xA578CDFF.toInt(), 0x2E78CDFF, 0x7878CDFF, 0xFF78CDFF.toInt(), false)

private fun al(col: Int, a: Float): Int = (col and 0x00FFFFFF) or ((a * 255f).toInt().coerceIn(0, 255) shl 24)

/**
 * Condensed bold for numerals and labels, a heavy compressed serif for the gear and clock, a Japanese serif for the
 * kanji (the system's own serif unless you add your own assets/fonts/JpSerif.ttf), mono for data.
 */
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
    /** Kanji labels. Drop a Japanese serif (for example Shippori Mincho) in as fonts/JpSerif.ttf to replace the system one. */
    val jp = load("fonts/JpSerif.ttf", null, Typeface.create(Typeface.SERIF, Typeface.BOLD))
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
    /** Which screen shows. Changing it replays the boot reveal. */
    var mode = Mode.SPORT
        set(v) {
            field = v
            bootT = SystemClock.uptimeMillis()
        }
    /** Called after the MODE button changed [mode], so the activity can save it. */
    var onMode: (() -> Unit)? = null
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
    private var bootT = SystemClock.uptimeMillis()
    private val clockShort = SimpleDateFormat("HH:mm", Locale.US)
    private var hexBmp: Bitmap? = null
    private var hexKey = ""
    private var lastLogAt = 0L
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
        lastLogAt = SystemClock.uptimeMillis()
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

    private fun pal(): Pal = when (mode) {
        Mode.SPORT, Mode.COMMAND -> PAL_SPORT
        Mode.ECO -> PAL_ECO
        Mode.COMFORT -> PAL_CALM
    }

    /** Text. [glow] > 0 adds the soft halo the reference puts on its big numerals. */
    private fun tx(
        c: Canvas, s: String, x: Float, y: Float, size: Float, col: Int, tf: Typeface,
        a: Paint.Align = Paint.Align.LEFT, ls: Float = 0f, sx: Float = 1f, glow: Float = 0f
    ) {
        p.style = Paint.Style.FILL
        p.typeface = tf
        p.textSize = size
        p.color = col
        p.textAlign = a
        p.letterSpacing = ls
        p.textScaleX = sx
        if (glow > 0f) p.setShadowLayer(glow, 0f, 0f, al(col, 0.75f))
        c.drawText(s, x, y, p)
        p.clearShadowLayer()
        p.textScaleX = 1f
    }

    private fun mw(s: String, size: Float, tf: Typeface, ls: Float = 0f, sx: Float = 1f): Float {
        p.typeface = tf
        p.textSize = size
        p.letterSpacing = ls
        p.textScaleX = sx
        val w = p.measureText(s)
        p.textScaleX = 1f
        return w
    }

    /** The largest size up to [maxSize] at which [s] still fits in [maxW]. */
    private fun fitSize(s: String, maxW: Float, maxSize: Float, tf: Typeface, sx: Float = 1f): Float {
        val w = mw(s, maxSize, tf, 0f, sx)
        return if (w > maxW && w > 0f) maxSize * maxW / w else maxSize
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

    /** Kanji label in the serif, with its English tag small beside it ("油温 OIL"). Returns the right edge. */
    private fun lbl(c: Canvas, jp: String, en: String, x: Float, base: Float, size: Float, pl: Pal, glow: Float = 0f): Float {
        tx(c, jp, x, base, size, pl.tag, f.jp, glow = glow)
        val x2 = x + mw(jp, size, f.jp) + 5 * d
        tx(c, en, x2, base, size * 0.62f, pl.sub, f.stamp, ls = 0.08f)
        return x2 + mw(en, size * 0.62f, f.stamp, 0.08f)
    }

    private fun brackets(c: Canvas, r: RectF, col: Int, glow: Float = 0f) {
        val len = 22 * d
        val wd = 2.5f * d
        if (glow > 0f) p.setShadowLayer(glow, 0f, 0f, al(col, 0.8f))
        fill(c, r.left, r.top, r.left + len, r.top + wd, col)
        fill(c, r.left, r.top, r.left + wd, r.top + len, col)
        fill(c, r.right - len, r.top, r.right, r.top + wd, col)
        fill(c, r.right - wd, r.top, r.right, r.top + len, col)
        fill(c, r.left, r.bottom - wd, r.left + len, r.bottom, col)
        fill(c, r.left, r.bottom - len, r.left + wd, r.bottom, col)
        fill(c, r.right - len, r.bottom - wd, r.right, r.bottom, col)
        fill(c, r.right - wd, r.bottom - len, r.right, r.bottom, col)
        p.clearShadowLayer()
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

    private fun segBar(c: Canvas, r: RectF, n: Int, lit: Int, col: Int, off: Int) {
        val gap = 2 * d
        val sw = (r.width() - gap * (n - 1)) / n
        for (i in 0 until n) {
            val x = r.left + i * (sw + gap)
            fill(c, x, r.top, x + sw, r.bottom, if (i < lit) col else off)
        }
    }

    /** Boot reveal: panels cut in one after another with a short flicker. Skipped when animations are off. */
    private fun shown(i: Int, now: Long): Boolean {
        if (!animOn) return true
        val e = now - bootT - i * 150L
        return e >= 0 && (e >= 130 || (e / 45) % 2L == 0L)
    }

    /** The honeycomb ground of the Sport and Command screens, drawn once into a bitmap. */
    private fun honeycomb(c: Canvas, col: Int) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val key = "$w x $h $col"
        var bmp = hexBmp
        if (bmp == null || key != hexKey) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bc = Canvas(bmp)
            val bp = Paint(Paint.ANTI_ALIAS_FLAG)
            bp.style = Paint.Style.STROKE
            bp.strokeWidth = d
            bp.color = al(col, 0.07f)
            val rad = 17 * d
            val dx = rad * 1.7320508f
            val dy = rad * 1.5f
            val hp = Path()
            var row = 0
            var cy = 0f
            while (cy < h + rad) {
                var cx = if (row % 2 == 0) 0f else dx / 2
                while (cx < w + rad) {
                    hp.reset()
                    for (i in 0 until 6) {
                        val a = Math.PI / 3 * i + Math.PI / 6
                        val x = cx + rad * cos(a).toFloat()
                        val y = cy + rad * sin(a).toFloat()
                        if (i == 0) hp.moveTo(x, y) else hp.lineTo(x, y)
                    }
                    hp.close()
                    bc.drawPath(hp, bp)
                    cx += dx
                }
                cy += dy
                row++
            }
            hexBmp?.recycle()
            hexBmp = bmp
            hexKey = key
        }
        c.drawBitmap(bmp!!, 0f, 0f, null)
    }

    // ---- seven-segment digits (the Eco Pro range) -------------------------------------------

    private fun segOn(ch: Char): String = when (ch) {
        '0' -> "abcdef"
        '1' -> "bc"
        '2' -> "abdeg"
        '3' -> "abcdg"
        '4' -> "bcfg"
        '5' -> "acdfg"
        '6' -> "acdefg"
        '7' -> "abc"
        '8' -> "abcdefg"
        '9' -> "abcdfg"
        '-' -> "g"
        else -> ""
    }

    private fun hSeg(cx: Float, cy: Float, len: Float, t: Float) {
        path.reset()
        path.moveTo(cx - len / 2, cy)
        path.lineTo(cx - len / 2 + t / 2, cy - t / 2)
        path.lineTo(cx + len / 2 - t / 2, cy - t / 2)
        path.lineTo(cx + len / 2, cy)
        path.lineTo(cx + len / 2 - t / 2, cy + t / 2)
        path.lineTo(cx - len / 2 + t / 2, cy + t / 2)
        path.close()
    }

    private fun vSeg(cx: Float, cy: Float, len: Float, t: Float) {
        path.reset()
        path.moveTo(cx, cy - len / 2)
        path.lineTo(cx + t / 2, cy - len / 2 + t / 2)
        path.lineTo(cx + t / 2, cy + len / 2 - t / 2)
        path.lineTo(cx, cy + len / 2)
        path.lineTo(cx - t / 2, cy + len / 2 - t / 2)
        path.lineTo(cx - t / 2, cy - len / 2 + t / 2)
        path.close()
    }

    /** One slanted seven-segment cell. Unlit segments are always drawn faintly, as on a real display. */
    private fun seg7(c: Canvas, x: Float, y: Float, w: Float, h: Float, ch: Char?, on: Int, off: Int) {
        val t = w * 0.19f
        val x0 = x + t / 2
        val x1 = x + w - t / 2
        val y0 = y + t / 2
        val ym = y + h / 2
        val y1 = y + h - t / 2
        val hl = (x1 - x0) - t * 0.35f
        val vl = (ym - y0) - t * 0.35f
        val lit = if (ch == null) "" else segOn(ch)
        c.save()
        c.translate(x, y + h)
        c.skew(-0.12f, 0f)
        c.translate(-x, -(y + h))
        p.style = Paint.Style.FILL
        for (pass in 0..1) {
            for (sg in "abcdefg") {
                val isOn = lit.indexOf(sg) >= 0
                if ((pass == 0) == isOn) continue
                when (sg) {
                    'a' -> hSeg((x0 + x1) / 2, y0, hl, t)
                    'b' -> vSeg(x1, (y0 + ym) / 2, vl, t)
                    'c' -> vSeg(x1, (ym + y1) / 2, vl, t)
                    'd' -> hSeg((x0 + x1) / 2, y1, hl, t)
                    'e' -> vSeg(x0, (ym + y1) / 2, vl, t)
                    'f' -> vSeg(x0, (y0 + ym) / 2, vl, t)
                    else -> hSeg((x0 + x1) / 2, ym, hl, t)
                }
                p.color = if (isOn) on else off
                if (isOn) p.setShadowLayer(9 * d, 0f, 0f, al(on, 0.8f))
                c.drawPath(path, p)
                p.clearShadowLayer()
            }
        }
        c.restore()
    }

    /** [s] right-aligned in [cells] cells that end at [right]. Returns the left edge of the first cell. */
    private fun seg7Row(c: Canvas, s: String, cells: Int, right: Float, y: Float, cw: Float, h: Float, on: Int, off: Int): Float {
        val gap = cw * 0.22f
        val left = right - cells * cw - (cells - 1) * gap
        for (i in 0 until cells) {
            val k = i - (cells - s.length)
            seg7(c, left + i * (cw + gap), y, cw, h, if (k >= 0) s[k] else null, on, off)
        }
        return left
    }

    // ---- data rows -------------------------------------------------------------------------

    private fun numStr(s: VehicleState, key: String, v: Float?, dec: Int = 0): String =
        if (v == null) (if (s.unsupported.contains(key)) "N/A" else "--")
        else if (dec == 0) v.roundToInt().toString()
        else String.format(Locale.US, "%.${dec}f", v)

    private fun trendText(t: Trend): String {
        val r = t.perMinute() ?: return "TREND --"
        val a = abs(r)
        return if (a < 0.5f) "STEADY" else (if (r > 0f) "RISING " else "FALLING ") + String.format(Locale.US, "%.1f", a) + "/MIN"
    }

    private class Row(
        val en: String, val jp: String, val value: String, val unit: String, val missing: Boolean,
        val sub1: String = "", val sub2: String = "", val warn: Boolean = false
    )

    /** Temperatures are information only: value, trend and trip maximum. No limits are applied. */
    private fun tempRow(en: String, jp: String, s: VehicleState, key: String, v: Float?, tr: Trend): Row {
        if (v == null) return Row(en, jp, numStr(s, key, null), "\u00b0C", true, if (s.unsupported.contains(key)) "NOT SUPPORTED BY CAR" else "")
        return Row(en, jp, numStr(s, key, v), "\u00b0C", false, trendText(tr), tr.max?.let { "MAX ${it.roundToInt()}\u00b0C" } ?: "")
    }

    private fun speedRow(s: VehicleState): Row =
        Row("SPEED", "\u901f\u5ea6", numStr(s, "spd", s.speedKmh), "KM/H", s.speedKmh == null)

    private fun readingRows(s: VehicleState): List<Row> = listOf(
        tempRow("OIL", "\u6cb9\u6e29", s, "oil", s.oilC, oilTrend),
        tempRow("WATER", "\u6c34\u6e29", s, "clt", s.coolantC, cltTrend),
        Row("MAP", "\u5438\u6c17\u5727", numStr(s, "map", s.mapKpa), "kPa", s.mapKpa == null),
        Row("LOAD", "\u8ca0\u8377", numStr(s, "load", s.loadPct), "%", s.loadPct == null),
        Row("VOLTS", "\u96fb\u5727", numStr(s, "vlt", s.volts, 1), "V", s.volts == null, warn = s.voltLevel() == Level.CAUTION),
    )

    /** The reference's reading column: kanji label left, big condensed value right with its unit, a rule between rows. */
    private fun readRows(c: Canvas, r: RectF, rows: List<Row>, pl: Pal) {
        val rh = r.height() / rows.size
        for ((i, row) in rows.withIndex()) {
            val y = r.top + i * rh
            if (i > 0) fill(c, r.left, y, r.right, y + d, pl.wire)
            val vs = min(rh * 0.78f, 40 * d) * (if (row.missing) 0.62f else 1f)
            val col = if (row.missing) pl.sub else if (row.warn) K.WAIT else pl.fg
            val unitW = mw(row.unit, 10 * d, f.stamp, 0.05f)
            val base = y + rh * 0.5f + vs * 0.35f
            tx(c, row.unit, r.right - 2 * d, base, 10 * d, pl.sub, f.stamp, Paint.Align.RIGHT, ls = 0.05f)
            tx(c, row.value, r.right - unitW - 8 * d, base, vs, col, f.stamp, Paint.Align.RIGHT, glow = if (row.missing) 0f else 5 * d)
            val lsz = min(15 * d, rh * 0.42f)
            val tall = rh > 46 * d
            lbl(c, row.jp, row.en, r.left + 2 * d, if (tall) y + rh * 0.42f else y + rh * 0.5f + lsz * 0.35f, lsz, pl)
            if (tall) {
                tx(c, fit(row.sub1, r.width() * 0.5f, 9 * d, f.mono), r.left + 2 * d, y + rh * 0.42f + 13 * d, 9 * d, pl.sub, f.mono)
                tx(c, fit(row.sub2, r.width() * 0.5f, 9 * d, f.mono), r.left + 2 * d, y + rh * 0.42f + 25 * d, 9 * d, pl.sub, f.mono)
            }
        }
    }

    /** A big number with its kanji label above and its unit under the right end (speed, rpm, fuel use...). */
    private fun statBlock(c: Canvas, r: RectF, jp: String, en: String, value: String, unit: String, missing: Boolean, pl: Pal) {
        lbl(c, jp, en, r.left + 2 * d, r.top + 16 * d, 15 * d, pl)
        val unitH = 14 * d
        val maxH = r.height() - 22 * d - unitH
        val tf = f.stamp
        val size = fitSize(value, r.width() - 4 * d, max(16 * d, maxH / 0.72f) * (if (missing) 0.4f else 1f), tf)
        val base = r.bottom - unitH - 2 * d
        tx(c, value, r.left + 2 * d, base, size, if (missing) pl.sub else pl.fg, tf, glow = if (missing) 0f else 7 * d)
        tx(c, unit, r.right - 2 * d, r.bottom - 2 * d, 10 * d, pl.sub, f.stamp, Paint.Align.RIGHT, ls = 0.08f)
    }

    // ---- status strip and rev bar ------------------------------------------------------------

    private fun statusText(s: VehicleState): Pair<String, String> = when {
        link == LinkState.CONNECTING -> Pair("CONNECTING", "\u63a5\u7d9a\u4e2d")
        !sample && link == LinkState.NONE -> Pair("STANDBY", "\u5f85\u6a5f")
        (s.rpm ?: 0f) > RpmScale.DIAL_MAX -> Pair("OVER DIAL SCALE", "\u76ee\u76db\u8d85\u904e")
        (s.loadPct ?: 0f) >= 90f -> Pair("FULL THROTTLE", "\u5168\u529b\u5168\u958b")
        else -> Pair("NOMINAL", "\u901a\u5e38\u7a3c\u50cd")
    }

    /** Top strip: link state left, "SYSTEM STATUS" centre (or the newest log line), clock right, then the rev bar. Returns the next y. */
    private fun statusBlock(c: Canvas, l: Float, r: Float, y: Float, now: Long, s: VehicleState, pl: Pal): Float {
        val rowH = 20 * d
        val barTop = y + rowH + 2 * d
        val barH = 12 * d
        val total = rowH + 2 * d + barH + 12 * d + 12 * d
        if (!shown(0, now)) return y + total
        val (label, col) = when {
            link == LinkState.LIVE -> Pair("LINK LIVE", K.GREEN)
            link == LinkState.CONNECTING -> Pair("CONNECTING", pl.fg)
            sample -> Pair("SAMPLE DATA", pl.fg)
            else -> Pair("NO LINK", K.DIM)
        }
        p.style = Paint.Style.FILL
        p.color = col
        c.drawCircle(l + 4 * d, y + 11 * d, 3.5f * d, p)
        tx(c, label, l + 12 * d, y + 16 * d, 13 * d, col, f.stamp, ls = 0.1f)
        tx(c, clock.format(Date()), r, y + 16 * d, 13 * d, pl.sub, f.mono, Paint.Align.RIGHT)
        // Optional emblem: drop your own `emblem` drawable into res/drawable and it is drawn beside the clock. None is included.
        if (emblemId != 0) {
            val dr = resources.getDrawable(emblemId, null)
            val ex = r - 78 * d
            dr.setBounds((ex - 20 * d).toInt(), (y + 1 * d).toInt(), ex.toInt(), (y + 21 * d).toInt())
            dr.draw(c)
        }

        val mid = (l + r) / 2
        val recent = lines.lastOrNull()
        if (recent != null && now - lastLogAt < 8000L) {
            val lc = when (recent.second) {
                Level.FAULT -> K.RED
                Level.CAUTION -> K.WAIT
                else -> pl.tag
            }
            val room = (r - l) * 0.5f
            tx(c, fit(recent.first, room, 12 * d, f.mono), mid, y + 15 * d, 12 * d, lc, f.mono, Paint.Align.CENTER)
        } else {
            val (st, jp) = statusText(s)
            val sz = 14 * d
            // the English is set in the stamp face and the kanji in the serif, side by side and centred together
            val head = "SYSTEM STATUS: $st // "
            val w1 = mw(head, sz, f.stamp, 0.05f)
            val w2 = mw(jp, sz, f.jp)
            val x0 = mid - (w1 + w2) / 2
            val hot = (s.loadPct ?: 0f) >= 90f
            tx(c, head, x0, y + 16 * d, sz, if (hot) K.HOT else pl.tag, f.stamp, ls = 0.05f)
            tx(c, jp, x0 + w1, y + 16 * d, sz, if (hot) K.HOT else pl.tag, f.jp)
        }
        revBar(c, l, r, barTop, barTop + barH, s.rpm, pl)
        return y + total
    }

    /** 40-segment bar on the non-linear dial. Orange up to 60% then yellow in Sport/Command, one colour elsewhere. No red zone: no verified limit exists. */
    private fun revBar(c: Canvas, l: Float, r: Float, top: Float, bottom: Float, rpm: Float?, pl: Pal) {
        val n = 40
        val lit = if (rpm == null) 0 else (RpmScale.fraction(rpm) * n).roundToInt().coerceIn(0, n)
        val gap = 2 * d
        val sw = (r - l - gap * (n - 1)) / n
        for (i in 0 until n) {
            val x = l + i * (sw + gap)
            val on = if (pl.ramp && i.toFloat() / n >= 0.6f) pl.mid else pl.fg
            fill(c, x, top, x + sw, bottom, if (i < lit) on else pl.faint)
        }
        // ticks at their true positions on the non-linear dial: 500 rpm minor, 1,000 rpm major
        var k = 0
        while (k <= 7000) {
            val x = l + RpmScale.fraction(k.toFloat()) * (r - l)
            val major = k % 1000 == 0
            fill(c, x - d / 2, bottom + 2 * d, x + d / 2, bottom + (if (major) 7 else 4) * d, if (major) pl.sub else pl.wire)
            if (major) tx(c, (k / 1000).toString(), x, bottom + 17 * d, 9 * d, pl.sub, f.mono, Paint.Align.CENTER)
            k += 500
        }
    }

    // ---- gear frame and MAGI ---------------------------------------------------------------

    /** The bracketed frame with the giant gear numeral. The gear is estimated from rpm / speed (the gearbox computer is not read). */
    private fun gearFrame(c: Canvas, r: RectF, pl: Pal) {
        frame(c, r, pl.wire, 1f)
        brackets(c, r, pl.fg, 6 * d)
        lbl(c, "\u4e3b\u5909\u901f\u5668", "CURRENT GEAR", r.left + 12 * d, r.top + 24 * d, 16 * d, pl)
        if (!sample && link == LinkState.NONE) {
            // 出撃待命 = "sortie standby", shown while no data source is linked
            val big = min(r.height() * 0.30f, r.width() * 0.20f)
            tx(c, "\u51fa\u6483\u5f85\u547d", r.centerX(), r.top + r.height() * 0.58f, big, pl.fg, f.jp, Paint.Align.CENTER, glow = 10 * d)
            tx(c, "SORTIE STANDBY", r.centerX(), r.bottom - 34 * d, 13 * d, pl.sub, f.stamp, Paint.Align.CENTER, ls = 0.25f)
        } else {
            val g = gearNow()
            val str = g?.toString() ?: "-"
            val avail = r.height() - 70 * d
            val size = fitSize(str, r.width() * 0.6f, avail / 0.72f * (if (g == null) 0.45f else 1f), f.title, 0.9f)
            val base = r.top + 34 * d + avail / 2 + size * 0.36f
            tx(c, str, r.centerX(), base, size, if (g == null) pl.sub else pl.fg, f.title, Paint.Align.CENTER, sx = 0.9f, glow = if (g == null) 0f else 22 * d)
        }
        val k = gearRatioNow()
        tx(c, "\u5909\u901f\u6a5f", r.left + 12 * d, r.bottom - 12 * d, 13 * d, pl.tag, f.jp)
        tx(c, "UNIT-01", r.left + 12 * d + mw("\u5909\u901f\u6a5f", 13 * d, f.jp) + 5 * d, r.bottom - 12 * d, 11 * d, pl.sub, f.stamp, ls = 0.08f)
        val est = if (k == null) "EST --" else "EST " + String.format(Locale.US, "%.1f", k) + " RPM/KMH"
        tx(c, est, r.right - 12 * d, r.bottom - 12 * d, 11 * d, pl.sub, f.stamp, Paint.Align.RIGHT, ls = 0.08f)
    }

    private fun plate(c: Canvas, r: RectF, cut: Float) {
        path.reset()
        path.moveTo(r.left + cut, r.top)
        path.lineTo(r.right - cut, r.top)
        path.lineTo(r.right, r.top + cut)
        path.lineTo(r.right, r.bottom - cut)
        path.lineTo(r.right - cut, r.bottom)
        path.lineTo(r.left + cut, r.bottom)
        path.lineTo(r.left, r.bottom - cut)
        path.lineTo(r.left, r.top + cut)
        path.close()
    }

    /**
     * The MAGI sortie check. Each of the three plates votes 承認 once its reading has arrived and 審議中 until then.
     * This is a signal check, not a health verdict: the app applies no engine limits (see Limits), so nothing here can say a value is bad.
     */
    private fun magiRow(c: Canvas, r: RectF, s: VehicleState, pl: Pal) {
        lbl(c, "\u51fa\u6483\u5224\u5b9a", "MAGI SYSTEM", r.left + 2 * d, r.top + 13 * d, 13 * d, pl)
        val drive = s.speedKmh != null && s.rpm != null
        val votes = listOf(
            Triple("MELCHIOR\u00b71", "\u6cb9\u6e29", s.oilC != null),
            Triple("BALTHASAR\u00b72", "\u6c34\u6e29", s.coolantC != null),
            Triple("CASPER\u00b73", "\u99c6\u52d5", drive),
        )
        val allOk = votes.all { it.third }
        val top = r.top + 20 * d
        val gap = 8 * d
        val verdictW = r.width() * 0.30f
        val pw = (r.width() - verdictW - gap * 3) / 3
        for ((i, v) in votes.withIndex()) {
            val pr = RectF(r.left + i * (pw + gap), top, r.left + i * (pw + gap) + pw, r.bottom)
            val ok = v.third
            val line = if (ok) K.OK else K.WAIT
            val soft = if (ok) K.OK_SOFT else K.WAIT_SOFT
            plate(c, pr, 7 * d)
            p.style = Paint.Style.FILL
            p.color = al(line, if (ok) 0.09f else 0.10f)
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * d
            p.color = line
            c.drawPath(path, p)
            tx(c, v.first, pr.centerX(), pr.top + 12 * d, 9 * d, soft, f.stamp, Paint.Align.CENTER, ls = 0.06f)
            val vote = if (ok) "\u627f\u8a8d" else "\u5be9\u8b70\u4e2d"
            val vs = min(pr.height() * 0.34f, fitSize(vote, pw - 12 * d, 24 * d, f.jp))
            tx(c, vote, pr.centerX(), pr.top + pr.height() * 0.67f, vs, line, f.jp, Paint.Align.CENTER, glow = 4 * d)
            tx(c, v.second, pr.centerX(), pr.bottom - 4 * d, 10 * d, soft, f.jp, Paint.Align.CENTER)
        }
        val vx = r.right - verdictW / 2
        val word = if (allOk) "\u5168\u4f1a\u4e00\u81f4" else "\u5be9\u8b70\u4e2d"
        val vc = if (allOk) K.OK else K.WAIT
        val vsz = fitSize(word, verdictW, 40 * d, f.jp)
        tx(c, word, vx, top + (r.bottom - top) * 0.55f, vsz, vc, f.jp, Paint.Align.CENTER, glow = 8 * d)
        tx(c, if (allOk) "UNANIMOUS // \u51fa\u6483\u627f\u8a8d" else "PENDING // \u5224\u5b9a\u4fdd\u7559", vx, r.bottom - 4 * d, 10 * d, pl.sub, f.stamp, Paint.Align.CENTER, ls = 0.06f)
    }

    // ---- the four screens ------------------------------------------------------------------

    private fun speedStr(s: VehicleState) = numStr(s, "spd", s.speedKmh)

    /** Sport: speed and revs left, the gear in its frame, five readings right. */
    private fun sportScreen(c: Canvas, r: RectF, s: VehicleState, pl: Pal, now: Long) {
        val gap = 10 * d
        val lw = r.width() * 0.26f
        val cw = r.width() * 0.40f
        val left = RectF(r.left, r.top, r.left + lw, r.bottom)
        val mid = RectF(left.right + gap, r.top, left.right + gap + cw, r.bottom)
        val right = RectF(mid.right + gap, r.top, r.right, r.bottom)
        if (shown(1, now)) {
            val half = left.height() * 0.5f
            statBlock(c, RectF(left.left, left.top, left.right, left.top + half - gap / 2), "\u901f\u5ea6", "SPEED", speedStr(s), "KM/H", s.speedKmh == null, pl)
            fill(c, left.left, left.top + half, left.right, left.top + half + d, pl.wire)
            statBlock(c, RectF(left.left, left.top + half + gap / 2, left.right, left.bottom), "\u56de\u8ee2\u6570", "RPM", numStr(s, "rpm", s.rpm), "RPM", s.rpm == null, pl)
        }
        if (shown(2, now)) gearFrame(c, mid, pl)
        if (shown(3, now)) readRows(c, right, readingRows(s), pl)
    }

    /** Command: the gear frame beside the readings, and the MAGI sortie check under both. */
    private fun commandScreen(c: Canvas, r: RectF, s: VehicleState, pl: Pal, now: Long) {
        val gap = 10 * d
        val magiH = if (r.height() > 210 * d) 78 * d else 0f
        val top = RectF(r.left, r.top, r.right, r.bottom - (if (magiH > 0f) magiH + gap else 0f))
        val gw = top.width() * 0.40f
        val gr = RectF(top.left, top.top, top.left + gw, top.bottom)
        val rr = RectF(gr.right + gap, top.top, top.right, top.bottom)
        if (shown(1, now)) gearFrame(c, gr, pl)
        if (shown(2, now)) readRows(c, rr, listOf(speedRow(s)) + readingRows(s).take(4), pl)
        if (magiH > 0f && shown(3, now)) magiRow(c, RectF(r.left, r.bottom - magiH, r.right, r.bottom), s, pl)
    }

    /** Eco Pro: the learned range in seven-segment digits inside a frame, with the line under it. */
    private fun ecoScreen(c: Canvas, r: RectF, s: VehicleState, pl: Pal, now: Long) {
        val gap = 10 * d
        val lw = r.width() * 0.24f
        val cw = r.width() * 0.46f
        val left = RectF(r.left, r.top, r.left + lw, r.bottom)
        val mid = RectF(left.right + gap, r.top, left.right + gap + cw, r.bottom)
        val right = RectF(mid.right + gap, r.top, r.right, r.bottom)
        val fm = fuelNow()
        if (shown(1, now)) {
            val half = left.height() * 0.5f
            statBlock(c, RectF(left.left, left.top, left.right, left.top + half - gap / 2), "\u901f\u5ea6", "SPEED", speedStr(s), "KM/H", s.speedKmh == null, pl)
            fill(c, left.left, left.top + half, left.right, left.top + half + d, pl.wire)
            val rate = fm.ratePer100
            statBlock(
                c, RectF(left.left, left.top + half + gap / 2, left.right, left.bottom), "\u71c3\u6599\u6d88\u8cbb", "L/100KM",
                if (rate == null) "--" else String.format(Locale.US, "%.1f", rate), "LEARNED AVG", rate == null, pl
            )
        }
        if (shown(2, now)) {
            frame(c, mid, pl.wire, 1f)
            brackets(c, mid, pl.fg, 6 * d)
            tx(c, "\u6d3b\u52d5\u9650\u754c\u307e\u3067", mid.left + 14 * d, mid.top + 28 * d, 20 * d, pl.tag, f.jp)
            tx(c, "ACTIVE RANGE REMAINING", mid.right - 14 * d, mid.top + 26 * d, 10 * d, pl.sub, f.stamp, Paint.Align.RIGHT, ls = 0.08f)
            val km = fm.rangeKm()
            val str = if (km == null) "" else min(km.roundToInt(), 9999).toString()
            val cells = 4
            val dh = min(mid.height() * 0.44f, mid.width() * 0.25f)
            val cwid = dh * 0.56f
            val rowW = cells * cwid + (cells - 1) * cwid * 0.22f
            val unitW = mw("KM", 14 * d, f.stamp, 0.1f)
            val right0 = mid.centerX() + (rowW - unitW) / 2
            val dy = mid.top + 40 * d
            val left0 = seg7Row(c, str, cells, right0, dy, cwid, dh, pl.fg, pl.faint)
            tx(c, "KM", right0 + 8 * d, dy + dh, 14 * d, pl.sub, f.stamp, ls = 0.1f)
            val note = when {
                km != null -> ""
                s.unsupported.contains("fuel") -> "NOT SUPPORTED BY CAR"
                fm.litres() == null -> "NO FUEL DATA"
                else -> "RANGE LEARNING"
            }
            if (note.isNotEmpty()) tx(c, note, left0, dy + dh + 16 * d, 11 * d, pl.sub, f.stamp, ls = 0.1f)
            val my = mid.bottom - 38 * d
            tx(c, "\u795e\u30cf\u5929\u30cb\u5728\u30ea", mid.centerX(), my, 20 * d, K.WHITE, f.jp, Paint.Align.CENTER)
            tx(c, "\u4e16\u30cf\u5168\u30c6\u4e8b\u30e2\u7121\u30b7", mid.centerX(), my + 24 * d, 20 * d, pl.fg, f.jp, Paint.Align.CENTER)
        }
        if (shown(3, now)) {
            tx(c, "\u7bc0\u96fb", right.right - 2 * d, right.top + 32 * d, 34 * d, pl.fg, f.jp, Paint.Align.RIGHT, glow = 8 * d)
            val body = RectF(right.left, right.top + 44 * d, right.right, right.bottom)
            val half = body.height() * 0.5f
            statBlock(c, RectF(body.left, body.top, body.right, body.top + half - gap / 2), "\u73fe\u5728\u6642\u523b", "TIME", clockShort.format(Date()), "", false, pl)
            val l = fm.litres()
            fill(c, body.left, body.top + half, body.right, body.top + half + d, pl.wire)
            statBlock(
                c, RectF(body.left, body.top + half + gap / 2, body.right, body.bottom), "\u71c3\u6599", "FUEL",
                if (l == null) numStr(s, "fuel", null) else String.format(Locale.US, "%.1f", l), "L", l == null, pl
            )
        }
    }

    /** Comfort: a big clock, the range, and a line of text. Nothing flashes. */
    private fun comfortScreen(c: Canvas, r: RectF, s: VehicleState, pl: Pal, now: Long) {
        val gap = 10 * d
        val lw = r.width() * 0.26f
        val rw = r.width() * 0.28f
        val left = RectF(r.left, r.top, r.left + lw, r.bottom)
        val right = RectF(r.right - rw, r.top, r.right, r.bottom)
        val mid = RectF(left.right + gap, r.top, right.left - gap, r.bottom)
        if (shown(1, now)) {
            val bh = min(left.height(), 150 * d)
            val sb = RectF(left.left, left.centerY() - bh / 2, left.right, left.centerY() + bh / 2)
            statBlock(c, sb, "\u901f\u5ea6", "SPEED", speedStr(s), "KM/H", s.speedKmh == null, pl)
        }
        if (shown(2, now)) {
            tx(c, "\u7b2c3\u65b0\u6771\u4eac\u5e02", mid.left + 4 * d, mid.top + 30 * d, 26 * d, pl.tag, f.jp, glow = 5 * d)
            val t = clockShort.format(Date())
            val hh = t.substringBefore(':')
            val mm = t.substringAfter(':')
            val size = fitSize(t, mid.width() - 8 * d, (mid.height() - 80 * d) / 0.72f, f.title, 0.92f)
            val base = mid.top + 40 * d + (mid.height() - 80 * d) / 2 + size * 0.36f
            val wh = mw(hh, size, f.title, 0f, 0.92f)
            val wc = mw(":", size, f.title, 0f, 0.92f)
            val x0 = mid.left + 4 * d
            tx(c, hh, x0, base, size, pl.fg, f.title, sx = 0.92f, glow = 14 * d)
            // the colon breathes slowly (4 s in and out), as in the reference
            val breath = if (animOn) 0.65f + 0.35f * sin((now % 4000L) / 4000f * 2f * Math.PI.toFloat()) else 1f
            tx(c, ":", x0 + wh, base, size, al(pl.fg, breath), f.title, sx = 0.92f, glow = 14 * d)
            tx(c, mm, x0 + wh + wc, base, size, pl.fg, f.title, sx = 0.92f, glow = 14 * d)
            val km = fuelNow().rangeKm()
            val rng = if (km == null) "-- KM" else "${km.roundToInt()} KM"
            tx(c, rng, x0 + 2 * d, mid.bottom - 6 * d, 16 * d, pl.sub, f.stamp, ls = 0.1f)
        }
        if (shown(3, now)) {
            val my = right.centerY()
            tx(c, "\u795e\u30cf\u5929\u30cb\u5728\u30ea", right.right, my - 4 * d, 22 * d, K.WHITE, f.jp, Paint.Align.RIGHT)
            tx(c, "\u4e16\u30cf\u5168\u30c6\u4e8b\u30e2\u7121\u30b7", right.right, my + 24 * d, 22 * d, K.WHITE, f.jp, Paint.Align.RIGHT)
        }
    }

    /** Fault screen, after the reference's alert: the kanji huge in red, the speed beside it. */
    private fun emergencyView(c: Canvas, r: RectF, s: VehicleState) {
        val size = min(r.height() * 0.46f, r.width() * 0.26f)
        val x = r.left + 8 * d
        tx(c, "\u975e\u5e38", x, r.top + size * 0.92f, size, K.ALERT, f.jp, glow = 14 * d)
        tx(c, "\u4e8b\u614b", x, r.top + size * 1.92f, size, K.ALERT, f.jp, glow = 14 * d)
        val sx = r.right - 8 * d
        tx(c, "SPEED \u901f\u5ea6", sx, r.top + 22 * d, 14 * d, K.ALERT, f.stamp, Paint.Align.RIGHT, ls = 0.12f)
        val str = speedStr(s)
        val sz = fitSize(str, r.width() * 0.42f, r.height() * 0.5f, f.stamp)
        tx(c, str, sx, r.top + 30 * d + sz * 0.8f, sz, K.WHITE, f.stamp, Paint.Align.RIGHT, glow = 8 * d)
        tx(c, "KM/H", sx, r.top + 36 * d + sz * 0.8f + 14 * d, 12 * d, K.DIM, f.stamp, Paint.Align.RIGHT, ls = 0.2f)
    }

    // ---- layout ----------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        val s = state
        val pl = pal()
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
        if (!mirror && !emergency && (mode == Mode.SPORT || mode == Mode.COMMAND)) honeycomb(c, pl.fg)

        c.save()
        if (mirror) c.scale(-1f, 1f, w / 2, h / 2)
        var y = if (mirror) pad else 6 * d
        if (!mirror) y = statusBlock(c, l0, r0, y, now, s, pl)
        if (alert != null) {
            banner(c, alert, pl, l0, y, r0, y + 50 * d, now)
            y += 56 * d
        }
        val bottom = h - pad - (if (mirror) 0f else barH)
        if (mirror) {
            val rpmTop = bottom - 96 * d
            val mainBottom = rpmTop - 6 * d
            if (emergency) emergencyView(c, RectF(l0, y, r0, mainBottom), s) else mirrorTiles(c, s, pl, l0, r0, y, mainBottom, 8 * d)
            if (shown(4, now)) rpmBlock(c, s, pl, l0, r0, rpmTop)
        } else {
            val area = RectF(l0, y, r0, bottom - 4 * d)
            if (emergency) {
                emergencyView(c, area, s)
            } else {
                when (mode) {
                    Mode.SPORT -> sportScreen(c, area, s, pl, now)
                    Mode.COMMAND -> commandScreen(c, area, s, pl, now)
                    Mode.ECO -> ecoScreen(c, area, s, pl, now)
                    Mode.COMFORT -> comfortScreen(c, area, s, pl, now)
                }
            }
        }
        c.restore()

        if (!mirror || now < barUntil) bottomBar(c, w, h, barH, pl)
    }

    private fun banner(c: Canvas, a: Alert, pl: Pal, l: Float, top: Float, r: Float, b: Float, now: Long) {
        val fault = a.level == Level.FAULT
        val base = if (fault) K.ALERT else pl.fg
        val col = if (fault && animOn && (now / 500) % 2L == 1L) (base and 0x00FFFFFF) or (0x70 shl 24) else base
        fill(c, l, top, r, b, if (fault) 0xFF1A0202.toInt() else K.PANEL)
        hazard(c, RectF(l, top, l + 40 * d, b), col)
        hazard(c, RectF(r - 40 * d, top, r, b), col)
        frame(c, RectF(l, top, r, b), col, 2f)
        val sx = l + 52 * d
        val sw = if (fault) 96 * d else 70 * d
        fill(c, sx, top + 8 * d, sx + sw, b - 8 * d, col)
        // fault: 非常事態 = "state of emergency"; caution: 警告 = "warning"; reminder (not a fault): 通知 = "notice"
        val stamp = if (fault) "\u975e\u5e38\u4e8b\u614b" else if (a.level == Level.NOMINAL) "\u901a\u77e5" else "\u8b66\u544a"
        tx(c, stamp, sx + sw / 2, (top + b) / 2 + 8 * d, if (fault) 21 * d else 26 * d, K.VOID, f.jp, Paint.Align.CENTER)
        val room = r - 48 * d - (sx + sw + 12 * d)
        tx(c, fit(a.text, room, 20 * d, f.stamp), sx + sw + 12 * d, (top + b) / 2 + 7 * d, 20 * d, col, f.stamp, ls = 0.04f)
    }

    private fun bottomBar(c: Canvas, w: Float, h: Float, bh: Float, pl: Pal) {
        val top = h - bh
        fill(c, 0f, top, w, h, K.VOID)
        fill(c, 0f, top, w, top + d, pl.fg)
        val names = listOf(if (link == LinkState.NONE) "LINK" else "UNLINK", "SAMPLE", "MIRROR", "MODE: " + mode.label)
        val on = listOf(link == LinkState.LIVE, sample, mirror, false)
        val bw = w / names.size
        for (i in names.indices) {
            val rc = RectF(i * bw, top, (i + 1) * bw, h)
            hits.add(Pair(rc, i))
            val col = if (on[i]) K.GREEN else if (i == 3) pl.fg else K.STEEL
            tx(c, names[i], rc.centerX(), rc.centerY() + 6 * d, 17 * d, col, f.stamp, Paint.Align.CENTER, ls = 0.2f)
            if (on[i]) fill(c, rc.left, h - 3 * d, rc.right, h, K.GREEN)
            if (i > 0) fill(c, rc.left, top, rc.left + d, h, pl.wire)
        }
    }

    // ---- mirror mode (windshield reflection) ------------------------------------------------

    private fun rpmBlock(c: Canvas, s: VehicleState, pl: Pal, l: Float, r: Float, top: Float) {
        val rpm = s.rpm
        val x2 = lbl(c, "\u56de\u8ee2\u6570", "ENGINE SPEED", l, top + 18 * d, 14 * d, pl)
        tx(c, rpm?.roundToInt()?.toString() ?: "--", r - 48 * d, top + 30 * d, 36 * d, K.WHITE, f.stamp, Paint.Align.RIGHT, glow = 5 * d)
        tx(c, "RPM", r, top + 30 * d, 14 * d, pl.sub, f.stamp, Paint.Align.RIGHT, ls = 0.15f)
        if (rpm != null && rpm > RpmScale.DIAL_MAX) {
            tx(c, "OVER DIAL SCALE", (x2 + r) / 2, top + 32 * d, 14 * d, K.HOT, f.stamp, Paint.Align.CENTER, ls = 0.15f)
        }
        val bt = top + 36 * d
        val bb = bt + 28 * d
        val n = 60
        val lit = if (rpm == null) 0 else (RpmScale.fraction(rpm) * n).roundToInt().coerceIn(0, n)
        segBar(c, RectF(l, bt, r, bb), n, lit, pl.fg, pl.faint)
        // Ticks sit at their true positions on the non-linear dial: 500 rpm minor, 1,000 rpm major.
        var k = 0
        while (k <= 7000) {
            val x = l + RpmScale.fraction(k.toFloat()) * (r - l)
            val major = k % 1000 == 0
            fill(c, x - d / 2, bb + 2 * d, x + d / 2, bb + (if (major) 8 else 5) * d, if (major) K.STEEL else pl.wire)
            if (major) tx(c, (k / 1000).toString(), x, bb + 20 * d, 11 * d, pl.sub, f.mono, Paint.Align.CENTER)
            k += 500
        }
    }

    private class TileData(val value: String, val unit: String, val color: Int, val sub1: String = "", val sub2: String = "")

    private fun num(s: VehicleState, key: String, v: Float?, dec: Int, unit: String, col: Int, sub1: String = "", sub2: String = ""): TileData {
        if (v == null) {
            val na = s.unsupported.contains(key)
            return TileData(if (na) "N/A" else "--", "", pal().sub, if (na) "NOT SUPPORTED BY CAR" else "")
        }
        val text = if (dec == 0) v.roundToInt().toString() else String.format(Locale.US, "%.${dec}f", v)
        return TileData(text, unit, col, sub1, sub2)
    }

    private fun tileData(t: TileType, s: VehicleState): TileData {
        val pl = pal()
        return when (t) {
            TileType.GEAR -> {
                val g = gearNow()
                val k = gearRatioNow()
                TileData(
                    g?.toString() ?: "-", "", if (g == null) pl.sub else pl.fg, "ESTIMATED FROM RPM / SPEED",
                    if (k == null) "" else "RATIO " + String.format(Locale.US, "%.1f", k) + " RPM PER KM/H"
                )
            }
            TileType.FUEL -> {
                val fm = fuelNow()
                val l = fm.litres()
                val km = fm.rangeKm()
                if (l == null) {
                    num(s, "fuel", null, 0, "", pl.sub)
                } else {
                    TileData(
                        String.format(Locale.US, "%.1f", l), "L", K.WHITE,
                        if (km != null) "RANGE ~${km.roundToInt()} KM" else "RANGE LEARNING",
                        "${(fm.pct ?: 0f).roundToInt()}% OF ${Car.TANK_L.roundToInt()} L TANK"
                    )
                }
            }
            TileType.COOLANT -> num(s, "clt", s.coolantC, 0, "\u00b0C", K.WHITE, trendText(cltTrend), cltTrend.max?.let { "MAX ${it.roundToInt()}\u00b0C" } ?: "")
            TileType.OIL -> num(s, "oil", s.oilC, 0, "\u00b0C", K.WHITE, trendText(oilTrend), oilTrend.max?.let { "MAX ${it.roundToInt()}\u00b0C" } ?: "")
            TileType.LOAD -> num(s, "load", s.loadPct, 0, "%", K.WHITE)
            TileType.MAP -> num(s, "map", s.mapKpa, 0, "kPa", K.WHITE)
            TileType.VOLTS -> num(s, "vlt", s.volts, 1, "V", if (s.voltLevel() == Level.CAUTION) K.WAIT else K.WHITE)
        }
    }

    private fun drawTile(c: Canvas, r: RectF, type: TileType, s: VehicleState, pl: Pal) {
        brackets(c, r, pl.fg, 5 * d)
        lbl(c, type.jp, type.label, r.left + 14 * d, r.top + 22 * d, 13 * d, pl)
        val t = tileData(type, s)
        val lines = if (r.height() >= 100 * d) 2 else if (r.height() >= 70 * d) 1 else 0
        val subs = listOf(t.sub1, t.sub2).filter { it.isNotEmpty() }.take(lines)
        val top = r.top + 30 * d
        val bot = r.bottom - 10 * d - subs.size * 12 * d
        val ah = max(14 * d, bot - top)
        val unitW = if (t.unit.isEmpty()) 0f else mw(t.unit, 16 * d, f.stamp, 0.1f) + 8 * d
        val tf = if (type == TileType.GEAR) f.title else f.stamp
        val sx = if (type == TileType.GEAR) 0.9f else 1f
        val maxW = r.width() - 32 * d - unitW
        val size = fitSize(t.value, maxW, ah * 1.1f, tf, sx)
        val vw = mw(t.value, size, tf, 0f, sx)
        val x0 = r.centerX() - (vw + unitW) / 2
        val base = top + (ah + size * 0.7f) / 2
        tx(c, t.value, x0, base, size, t.color, tf, sx = sx, glow = if (t.color == pl.sub) 0f else 10 * d)
        if (t.unit.isNotEmpty()) tx(c, t.unit, x0 + vw + 8 * d, base, 16 * d, pl.sub, f.stamp, ls = 0.1f)
        var y = r.bottom - 10 * d
        for (line in subs.reversed()) {
            tx(c, fit(line, r.width() - 20 * d, 9 * d, f.mono), r.centerX(), y, 9 * d, pl.sub, f.mono, Paint.Align.CENTER)
            y -= 12 * d
        }
    }

    /** The fixed speed block on the left of the mirror view. */
    private fun speedPanel(c: Canvas, r: RectF, s: VehicleState, pl: Pal) {
        brackets(c, r, pl.fg, 6 * d)
        lbl(c, "\u901f\u5ea6", "SPEED", r.left + 14 * d, r.top + 24 * d, 15 * d, pl)
        if (!sample && link == LinkState.NONE) {
            val big = min(r.height() * 0.30f, r.width() * 0.19f)
            tx(c, "\u51fa\u6483\u5f85\u547d", r.centerX(), r.top + r.height() * 0.55f, big, pl.fg, f.jp, Paint.Align.CENTER, glow = 10 * d)
            tx(c, "SORTIE STANDBY", r.centerX(), r.bottom - 12 * d, 14 * d, pl.sub, f.stamp, Paint.Align.CENTER, ls = 0.25f)
            return
        }
        val str = speedStr(s)
        val size = fitSize(str, r.width() * 0.8f, r.height() * 0.95f, f.stamp)
        tx(c, str, r.centerX(), r.top + r.height() * 0.5f + size * 0.34f, size, pl.fg, f.stamp, Paint.Align.CENTER, glow = 14 * d)
        tx(c, "KM/H", r.centerX(), r.bottom - 12 * d, 14 * d, pl.sub, f.stamp, Paint.Align.CENTER, ls = 0.25f)
    }

    /** Mirror layout: speed fixed on the left, the current page of tiles on the right. */
    private fun mirrorTiles(c: Canvas, s: VehicleState, pl: Pal, l: Float, r: Float, top: Float, bottom: Float, gap: Float) {
        val sp = RectF(l, top, l + (r - l) * 0.46f, bottom)
        speedPanel(c, sp, s, pl)
        val page = pages.pages[pages.index]
        val area = RectF(sp.right + gap, top, r, bottom)
        val bigW = if (page.size == 1) area.width() else area.width() * 0.58f
        val big = RectF(area.left, area.top, area.left + bigW, area.bottom)
        drawTile(c, big, page[0], s, pl)
        tileRects.add(big)
        val n = page.size - 1
        if (n > 0) {
            val th = (area.height() - gap * (n - 1)) / n
            for (i in 1..n) {
                val y = area.top + (i - 1) * (th + gap)
                val rc = RectF(big.right + gap, y, area.right, y + th)
                drawTile(c, rc, page[i], s, pl)
                tileRects.add(rc)
            }
        }
        for (i in pages.pages.indices) {
            val x = area.centerX() + (i - (pages.pages.size - 1) / 2f) * 10 * d
            fill(c, x - 3 * d, bottom + d, x + 3 * d, bottom + 5 * d, if (i == pages.index) pl.fg else pl.wire)
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
                    3 -> {
                        mode = Mode.values()[(mode.ordinal + 1) % Mode.values().size]
                        onMode?.invoke()
                    }
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
