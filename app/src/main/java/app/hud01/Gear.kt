package app.hud01

import kotlin.math.abs
import kotlin.math.min

/**
 * Estimates the engaged gear from engine speed divided by road speed. It learns one ratio per gear
 * while driving, so the number is an ESTIMATE (the gearbox computer is not read) and starts empty
 * until each gear has been used for a while. Gear 1 is the highest rpm-per-km/h ratio seen.
 */
class GearEstimator {
    private val ks = ArrayList<Float>()
    private val ns = ArrayList<Int>()
    private val recent = ArrayList<Float>()

    var gear: Int? = null
        private set

    /** The last steady rpm-per-km/h ratio, or null while it is not steady. */
    var ratio: Float? = null
        private set

    fun update(speed: Float?, rpm: Float?) {
        if (speed == null || rpm == null || speed < 20f || rpm < 900f) {
            recent.clear()
            gear = null
            ratio = null
            return
        }
        recent.add(rpm / speed)
        if (recent.size > 6) recent.removeAt(0)
        if (recent.size < 6) return
        var lo = recent[0]
        var hi = recent[0]
        var sum = 0f
        for (x in recent) {
            if (x < lo) lo = x
            if (x > hi) hi = x
            sum += x
        }
        if (hi / lo > 1.03f) {
            gear = null
            ratio = null // changing gear, clutch in or slipping: the ratio is not steady
            return
        }
        val k = sum / recent.size
        ratio = k
        val i = nearest(k, ks.indices.toList())
        if (i >= 0) {
            ns[i] = ns[i] + 1
            ks[i] = ks[i] + (k - ks[i]) / min(ns[i], 300).toFloat()
        } else if (ks.size < 8) {
            ks.add(k)
            ns.add(1)
        }
        gear = rank(k)
    }

    private fun nearest(k: Float, idx: List<Int>): Int {
        var best = -1
        var bd = 0.08f
        for (i in idx) {
            val dd = abs(ks[i] - k) / ks[i]
            if (dd < bd) {
                bd = dd
                best = i
            }
        }
        return best
    }

    /** Only ratios seen at least 20 times count as a gear. */
    private fun rank(k: Float): Int? {
        val sorted = ks.indices.filter { ns[it] >= 20 }.sortedByDescending { ks[it] }
        val i = nearest(k, sorted)
        return if (i < 0) null else sorted.indexOf(i) + 1
    }

    fun serialize(): String = ks.indices.joinToString(";") { "${ks[it]},${ns[it]}" }

    fun restore(s: String) {
        ks.clear()
        ns.clear()
        for (part in s.split(';')) {
            val x = part.split(',')
            if (x.size != 2) continue
            val k = x[0].toFloatOrNull()
            val n = x[1].toIntOrNull()
            if (k != null && n != null && k > 0f) {
                ks.add(k)
                ns.add(n)
            }
        }
    }
}
