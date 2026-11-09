package app.hud01

import kotlin.math.min

/** Facts about this car. */
object Car {
    /** Published fuel tank capacity of the F30 LCI 318i: 60 L on several spec sites (not BMW service data). */
    const val TANK_L = 60f
}

/**
 * Fuel level in litres, plus a range estimate learned from how this driver actually uses fuel.
 * Consumption is measured as litres used over distance driven (distance is integrated from speed),
 * averaged in after every measured stretch, so it keeps adjusting trip after trip. Until the first
 * stretch has been measured there is no range, never a made-up one.
 */
class FuelModel {
    var pct: Float? = null
        private set
    var ratePer100: Float? = null
        private set

    private var base: Float? = null
    private var baseKm = 0f
    private var lastT = 0L

    fun update(now: Long, speed: Float?, fuel: Float?) {
        if (lastT != 0L && speed != null) baseKm += speed * min(now - lastT, 5000L).toFloat() / 3_600_000f
        lastT = now
        if (fuel == null) return
        val prev = pct
        if (prev == null || fuel > prev + 3f) { // first reading, or a refuel
            pct = fuel
            base = fuel
            baseKm = 0f
            return
        }
        val cur = prev + (fuel - prev) * 0.05f // damp fuel slosh
        pct = cur
        val b = base ?: cur
        base = b
        val litres = (b - cur) / 100f * Car.TANK_L
        if (baseKm >= 5f && litres >= 0.7f) {
            val sample = litres / baseKm * 100f
            val old = ratePer100
            ratePer100 = if (old == null) sample else 0.7f * old + 0.3f * sample
            base = cur
            baseKm = 0f
        } else if (baseKm > 60f) {
            base = cur
            baseKm = 0f
        }
    }

    fun litres(): Float? = pct?.let { it / 100f * Car.TANK_L }

    fun rangeKm(): Float? {
        val r = ratePer100 ?: return null
        val l = litres() ?: return null
        return l / r * 100f
    }

    fun serialize(): String = ratePer100?.toString() ?: ""

    fun restore(s: String) {
        ratePer100 = s.toFloatOrNull()
    }
}
