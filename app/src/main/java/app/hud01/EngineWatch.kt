package app.hud01

/**
 * Detects "engine started" and "car turned off" from the live readings. Pure logic.
 *
 * Started: RPM at or above [RUN_RPM] on two readings in a row.
 * Turned off: the car stops answering entirely (no RPM and no speed) for [OFF_MS] after the engine
 * had been running. A start-stop pause is NOT treated as off: the engine control unit still answers
 * with 0 rpm then, so RPM is not "no data". The "ready" callback fires once per start-up, not after
 * every start-stop restart.
 */
class EngineWatch(private val onStarted: () -> Unit, private val onStopped: () -> Unit) {
    var running = false
        private set
    private var armed = true
    private var hits = 0
    private var noDataSince = 0L

    fun update(now: Long, s: VehicleState) {
        val rpm = s.rpm
        if (rpm != null && rpm >= RUN_RPM) {
            noDataSince = 0L
            if (hits < 2) hits++
            if (!running && hits >= 2) {
                running = true
                if (armed) {
                    armed = false
                    onStarted()
                }
            }
            return
        }
        hits = 0
        if (rpm == null && s.speedKmh == null) {
            if (noDataSince == 0L) noDataSince = now
            if (running && now - noDataSince >= OFF_MS) {
                running = false
                armed = true
                noDataSince = 0L
                onStopped()
            }
        } else {
            noDataSince = 0L
        }
    }

    fun reset() {
        running = false
        armed = true
        hits = 0
        noDataSince = 0L
    }

    companion object {
        /** Same engine-running threshold the voltage warning already uses. */
        const val RUN_RPM = 400f
        const val OFF_MS = 5000L
    }
}
