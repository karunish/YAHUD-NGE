package app.hud01

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var hud: HudView
    private val handler = Handler(Looper.getMainLooper())
    private var t = 0f
    private var obd: ObdLink? = null

    private val tick = object : Runnable {
        override fun run() {
            if (hud.sample) {
                t += 0.1f
                hud.state = SampleSource.at(t)
            }
            hud.invalidate()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hud = HudView(this)
        hud.onLink = { toggleLink() }
        hud.gearLive.restore(getSharedPreferences("hud", MODE_PRIVATE).getString("gear", "") ?: "")
        setContentView(hud)
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        handler.post(tick)
    }

    override fun onPause() {
        getSharedPreferences("hud", MODE_PRIVATE).edit().putString("gear", hud.gearLive.serialize()).apply()
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        obd?.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun toggleLink() {
        val running = obd
        if (running != null) {
            hud.userUnlink = true
            running.stop()
            return
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            hud.log("Allow Bluetooth access, then tap Link again", Level.CAUTION)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            hud.log("Bluetooth is off", Level.CAUTION)
            return
        }
        val dev = adapter.bondedDevices.firstOrNull { d ->
            val n = d.name ?: ""
            n.contains("NEXAS", true) || n.contains("OBD", true) || n.contains("ELM", true)
        }
        if (dev == null) {
            hud.log("No paired NexLink found. Pair it in Android settings, PIN 1234.", Level.CAUTION)
            return
        }
        hud.sample = false
        hud.state = VehicleState()
        hud.link = LinkState.CONNECTING
        val link = ObdLink(dev, handler, object : ObdLink.Listener {
            override fun onLive() {
                hud.link = LinkState.LIVE
                hud.invalidate()
            }

            override fun onState(s: VehicleState) {
                hud.state = s
                hud.invalidate()
            }

            override fun onLog(msg: String, level: Level) {
                hud.log(msg, level)
            }

            override fun onClosed() {
                obd = null
                hud.link = LinkState.NONE
                hud.state = VehicleState()
                hud.invalidate()
            }
        })
        obd = link
        link.start()
    }
}
