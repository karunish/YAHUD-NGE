package app.hud01

import java.util.Locale

enum class Level { NOMINAL, CAUTION, FAULT }

data class Alert(val level: Level, val text: String)

/** Every reading is null when the car did not provide it. Never substitute 0. */
data class VehicleState(
    val speedKmh: Float? = null,
    val rpm: Float? = null,
    val coolantC: Float? = null,
    val oilC: Float? = null,
    val loadPct: Float? = null,
    val mapKpa: Float? = null,
    val volts: Float? = null,
    val fuelPct: Float? = null,
    /** Keys ("oil", "vlt", ...) that the car reported as unsupported. Shown as N/A. */
    val unsupported: Set<String> = emptySet(),
)

/**
 * Warning limits. Only limits with a stated basis belong here.
 * Coolant, oil and RPM have NO limits on purpose: BMW publishes no single universal value for this
 * engine, so those readings are shown as information (value, trend, trip maximum) until a
 * validated source or a genuine BMW/DME warning state is available.
 */
object Limits {
    /** Provisional guess, not a BMW value. Applies only while the engine is running. */
    const val VOLT_LOW = 11.8f
}

/**
 * Visual mapping of RPM to a 0..1 position on the dial. Drawing only: warnings never read from here.
 * The scale is deliberately non-linear (1,000-3,500 rpm gets most of the width) and the dial
 * draws its ticks at their true positions so it never looks linear. DIAL_MAX is a drawing
 * choice, not an engine limit.
 */
object RpmScale {
    const val DIAL_MAX = 7000f
    private val xs = floatArrayOf(0f, 1000f, 3500f, 7000f)
    private val ys = floatArrayOf(0f, 0.08f, 0.78f, 1f)

    fun fraction(rpm: Float): Float {
        val r = rpm.coerceIn(0f, DIAL_MAX)
        for (i in 1 until xs.size) {
            if (r <= xs[i]) return ys[i - 1] + (r - xs[i - 1]) / (xs[i] - xs[i - 1]) * (ys[i] - ys[i - 1])
        }
        return 1f
    }
}

/** Least-squares trend over a sliding window, so one transient sample never moves it much. */
class Trend(private val windowMs: Long = 120_000L, private val minSpanMs: Long = 45_000L) {
    private val ts = ArrayList<Long>()
    private val vs = ArrayList<Float>()
    private var last = 0L

    var max: Float? = null
        private set

    fun add(now: Long, x: Float?) {
        if (x == null || now - last < 1000L) return
        last = now
        ts.add(now)
        vs.add(x)
        while (ts.isNotEmpty() && now - ts[0] > windowMs) {
            ts.removeAt(0)
            vs.removeAt(0)
        }
        val m = max
        if (m == null || x > m) max = x
    }

    /** Change per minute, or null until enough data has been seen. */
    fun perMinute(): Float? {
        if (ts.size < 5 || ts[ts.size - 1] - ts[0] < minSpanMs) return null
        val n = ts.size
        val t0 = ts[0]
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            val x = (ts[i] - t0) / 1000.0
            val y = vs[i].toDouble()
            sx += x
            sy += y
            sxx += x * x
            sxy += x * y
        }
        val den = n * sxx - sx * sx
        if (den <= 0.0) return null
        return (((n * sxy - sx * sy) / den) * 60.0).toFloat()
    }

    fun reset() {
        ts.clear()
        vs.clear()
        max = null
        last = 0L
    }
}

fun VehicleState.voltLevel(): Level {
    val v = volts ?: return Level.NOMINAL
    return if (v < Limits.VOLT_LOW && (rpm ?: 0f) > 400f) Level.CAUTION else Level.NOMINAL
}

fun VehicleState.alert(): Alert? {
    if (voltLevel() == Level.CAUTION) {
        return Alert(
            Level.CAUTION,
            "CAUTION: System voltage low, " + String.format(Locale.US, "%.1f", volts ?: 0f) + " V. Charging fault possible."
        )
    }
    return null
}
