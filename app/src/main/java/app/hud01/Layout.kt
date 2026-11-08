package app.hud01

import android.content.Context

/** Every HUD element the driver can move and resize. Order is also draw order (later = on top). */
enum class El(val label: String) {
    HEADER("HEADER"),
    SPEED("SPEED"),
    RPM("RPM"),
    CLT("COOLANT"),
    OIL("OIL"),
    LOAD("LOAD"),
    MAP("MAP"),
    VOLT("VOLTS"),
}

/** Offset as a fraction of screen width/height, plus a size multiplier, relative to the element's default spot. */
class Placement(var dx: Float = 0f, var dy: Float = 0f, var s: Float = 1f)

/**
 * Saved element positions. Normal view and mirror view keep separate sets, because the mirror view
 * uses a different default arrangement (header and readouts hidden, bigger speed and RPM).
 */
class LayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("hud01_layout", Context.MODE_PRIVATE)
    private val cache = HashMap<String, Placement>()

    private fun key(mirror: Boolean, e: El) = (if (mirror) "m." else "n.") + e.name

    fun get(mirror: Boolean, e: El): Placement {
        val k = key(mirror, e)
        return cache.getOrPut(k) {
            Placement(
                prefs.getFloat("$k.dx", 0f).coerceIn(-1f, 1f),
                prefs.getFloat("$k.dy", 0f).coerceIn(-1f, 1f),
                prefs.getFloat("$k.s", 1f).coerceIn(MIN_SCALE, MAX_SCALE),
            )
        }
    }

    fun save(mirror: Boolean) {
        val ed = prefs.edit()
        for (e in El.values()) {
            val k = key(mirror, e)
            val pl = cache[k] ?: continue
            ed.putFloat("$k.dx", pl.dx)
            ed.putFloat("$k.dy", pl.dy)
            ed.putFloat("$k.s", pl.s)
        }
        ed.apply()
    }

    fun reset(mirror: Boolean, e: El) {
        val pl = get(mirror, e)
        pl.dx = 0f
        pl.dy = 0f
        pl.s = 1f
        save(mirror)
    }

    fun resetAll(mirror: Boolean) {
        for (e in El.values()) {
            val pl = get(mirror, e)
            pl.dx = 0f
            pl.dy = 0f
            pl.s = 1f
        }
        save(mirror)
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 1.6f
    }
}
