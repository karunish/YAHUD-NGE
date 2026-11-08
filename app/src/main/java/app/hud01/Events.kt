package app.hud01

/** Detects engine start and stop from RPM, ignoring brief blips (cranking, a single missed reading). */
class EngineEvents {
    enum class Ev { STARTED, STOPPED }

    private var on = false
    private var onSince = 0L
    private var offSince = 0L

    fun reset() {
        on = false
        onSince = 0L
        offSince = 0L
    }

    /** Call with every RPM reading. Returns an event once, when a start or stop is confirmed. */
    fun update(now: Long, rpm: Float?): Ev? {
        val running = rpm != null && rpm > 400f
        if (running) {
            offSince = 0L
            if (!on) {
                if (onSince == 0L) onSince = now
                if (now - onSince >= 2000L) {
                    on = true
                    return Ev.STARTED
                }
            }
        } else {
            onSince = 0L
            if (on) {
                if (offSince == 0L) offSince = now
                if (now - offSince >= 3000L) {
                    on = false
                    return Ev.STOPPED
                }
            }
        }
        return null
    }
}
