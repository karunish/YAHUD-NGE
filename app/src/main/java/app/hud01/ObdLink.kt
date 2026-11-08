package app.hud01

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.SystemClock
import java.io.IOException
import java.util.UUID

/** Classic Bluetooth (RFCOMM) link to an ELM327 adapter. Runs on its own thread; every callback is posted to [main]. */
class ObdLink(private val device: BluetoothDevice, private val main: Handler, private val ui: Listener) {
    interface Listener {
        fun onLive()
        fun onState(s: VehicleState)
        fun onLog(msg: String, level: Level)
        fun onClosed()
    }

    private class P(val id: String, val key: String, val f: (Int, Int) -> Float) {
        val num = id.toInt(16)
    }

    private val pids = listOf(
        P("0C", "rpm") { a, b -> (a * 256 + b) / 4f },
        P("0D", "spd") { a, _ -> a.toFloat() },
        P("05", "clt") { a, _ -> a - 40f },
        P("04", "load") { a, _ -> a * 100f / 255f },
        P("0B", "map") { a, _ -> a.toFloat() },
        P("42", "vlt") { a, b -> (a * 256 + b) / 1000f },
        P("5C", "oil") { a, _ -> a - 40f },
    )

    @Volatile private var run = false
    private var sock: BluetoothSocket? = null
    private val vals = HashMap<String, Float?>()
    private val unsupported = HashSet<String>()
    private val seen = HashSet<String>()
    private val nulls = HashMap<String, Int>()
    private val names = mapOf(
        "rpm" to "Engine speed", "spd" to "Vehicle speed", "clt" to "Coolant temperature",
        "load" to "Engine load", "map" to "Manifold pressure", "vlt" to "Module voltage", "oil" to "Oil temperature",
    )
    private val label = device.name ?: "adapter"

    fun start() {
        run = true
        Thread({ work() }, "obd").start()
    }

    fun stop() {
        run = false
        try { sock?.close() } catch (e: IOException) { }
    }

    private fun log(m: String, l: Level = Level.NOMINAL) {
        main.post { ui.onLog(m, l) }
    }

    private fun open(): BluetoothSocket {
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        try {
            val s = device.createRfcommSocketToServiceRecord(uuid)
            s.connect()
            return s
        } catch (e: IOException) {
            val s = device.createInsecureRfcommSocketToServiceRecord(uuid)
            s.connect()
            return s
        }
    }

    /** Send one command and return the reply text up to the '>' prompt. */
    private fun tx(cmd: String, timeoutMs: Long): String {
        val input = sock!!.inputStream
        val out = sock!!.outputStream
        while (input.available() > 0) input.read()
        out.write((cmd + "\r").toByteArray())
        out.flush()
        val sb = StringBuilder()
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (run && SystemClock.uptimeMillis() < end) {
            if (input.available() > 0) {
                val ch = input.read()
                if (ch < 0) throw IOException("adapter closed the connection")
                if (ch.toChar() == '>') return sb.toString()
                sb.append(ch.toChar())
            } else {
                Thread.sleep(5)
            }
        }
        throw IOException("timeout on $cmd")
    }

    /** Returns all data bytes of a "41 <pid>" reply, or null for NO DATA and similar. */
    private fun parse(reply: String, pid: String): IntArray? {
        val hex = reply.split('\r', '\n')
            .filter { l -> l.isNotEmpty() && l.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' || it == ' ' } }
            .joinToString("").replace(" ", "").uppercase()
        val i = hex.indexOf("41$pid")
        if (i < 0) return null
        val d = hex.substring(i + 4)
        val n = d.length / 2
        if (n == 0) return null
        return IntArray(n) { d.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    /** Reads the car's own list of supported standard PIDs (01-20, 21-40, 41-60), or null if unreadable. */
    private fun supportedPids(): Set<Int>? {
        val set = HashSet<Int>()
        var any = false
        for (base in listOf(0x00, 0x20, 0x40)) {
            val id = String.format("%02X", base)
            val b = parse(tx("01$id", 4000L), id) ?: break
            if (b.size < 4) break
            any = true
            val mask = (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
            for (n in 1..32) if (((mask shr (32 - n)) and 1L) == 1L) set.add(base + n)
            if (!set.contains(base + 32)) break
        }
        return if (any) set else null
    }

    private fun markUnsupported(p: P, why: String) {
        if (unsupported.add(p.key)) {
            vals[p.key] = null
            log("${names[p.key]} (PID ${p.id}) is not supported by this car, shown as N/A ($why)")
        }
    }

    private fun work() {
        try {
            log("Connecting to $label...")
            sock = open()
            log("Bluetooth connected, starting adapter")
            val init = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")
            for ((i, c) in init.withIndex()) tx(c, if (i == 0) 5000L else 3000L)
            if (parse(tx("0100", 15000L), "00") == null) {
                throw IOException("car did not answer. Turn the ignition on and retry")
            }
            // Lock the protocol the adapter found, so it never re-searches mid-drive.
            val dn = tx("ATDPN", 3000L).filter { it.isDigit() || it in 'A'..'C' }.takeLast(1)
            if (dn.isNotEmpty() && dn != "0") tx("ATSP$dn", 3000L)
            val sup = supportedPids()
            if (sup == null) {
                log("Could not read the car's supported-PID list; probing each reading instead", Level.CAUTION)
            } else {
                log("Car reports ${sup.size} supported standard PIDs")
                for (p in pids) if (!sup.contains(p.num)) markUnsupported(p, "not in the car's supported list")
            }
            main.post { ui.onLive() }
            log("Linked to $label")
            loop()
        } catch (e: Exception) {
            if (run) log("Link failed: ${e.message}", Level.CAUTION)
        } finally {
            run = false
            try { sock?.close() } catch (e: IOException) { }
            sock = null
            main.post { ui.onClosed() }
            log("Adapter unlinked")
        }
    }

    private fun loop() {
        val fast = listOf(0, 1)
        val slowAll = listOf(2, 3, 4, 5, 6)
        var k = 0
        var bad = 0
        while (run) {
            val slow = slowAll.filter { pids[it].key !in unsupported }
            val extra = if (slow.isEmpty()) emptyList() else listOf(slow[k++ % slow.size])
            val q = (fast + extra).filter { pids[it].key !in unsupported }
            if (q.isEmpty()) Thread.sleep(200)
            for (i in q) {
                if (!run) break
                val p = pids[i]
                try {
                    val b = parse(tx("01" + p.id, 4000L), p.id)
                    if (b == null) {
                        vals[p.key] = null
                        val n = (nulls[p.key] ?: 0) + 1
                        nulls[p.key] = n
                        if (n >= 5 && !seen.contains(p.key)) markUnsupported(p, "no data after 5 requests")
                    } else {
                        vals[p.key] = p.f(b[0], if (b.size > 1) b[1] else 0)
                        seen.add(p.key)
                        nulls[p.key] = 0
                    }
                    bad = 0
                } catch (e: IOException) {
                    if (!run) break
                    bad++
                    vals[p.key] = null
                    if (bad >= 5) throw IOException("link lost: ${e.message}")
                    if (bad == 1) log("Slow reply from adapter, retrying", Level.CAUTION)
                    Thread.sleep(300)
                }
            }
            val s = VehicleState(
                vals["spd"], vals["rpm"], vals["clt"], vals["oil"], vals["load"], vals["map"], vals["vlt"],
                unsupported.toSet(),
            )
            main.post { ui.onState(s) }
        }
    }
}
