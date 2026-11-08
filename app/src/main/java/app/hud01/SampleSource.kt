package app.hud01

import kotlin.math.max
import kotlin.math.sin

/** Synthetic data for demo mode. Always labelled as sample data in the UI. */
object SampleSource {
    fun at(t: Float): VehicleState {
        val wave = sin(t / 9f)
        val speed = max(0f, 70f + 55f * wave)
        val p = max(0f, wave)
        return VehicleState(
            speedKmh = speed,
            rpm = 900f + speed * 28f + 60f * sin(t * 3f),
            coolantC = 90f + 24f * max(0f, sin(t / 25f) - 0.4f) / 0.6f,
            oilC = 85f + 12f * sin(t / 40f),
            loadPct = 20f + 40f * p,
            mapKpa = 60f + 80f * p,
            volts = if (t % 120f in 90f..105f) 11.4f else 14.1f,
        )
    }
}
