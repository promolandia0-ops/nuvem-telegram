package br.geracaoai.nuvem.work

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import br.geracaoai.nuvem.tg.Td

/** Mantém CPU, Wi-Fi e o TDLib acordados enquanto o backup sobe. */
class KeepAlive(c: Context) {
    private val app = c.applicationContext
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    fun acquire() {
        try {
            if (wake?.isHeld != true) {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nuvem:backup").also {
                    it.setReferenceCounted(false); it.acquire(6 * 60 * 60_000L)
                }
            }
        } catch (_: Exception) {}
        try {
            if (wifi?.isHeld != true) {
                val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nuvem:backup").also {
                    it.setReferenceCounted(false); it.acquire()
                }
            }
        } catch (_: Exception) {}
        try { Td.init(app) } catch (_: Exception) {}
    }

    fun release() {
        try { if (wifi?.isHeld == true) wifi?.release() } catch (_: Exception) {}
        wifi = null
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        wake = null
    }
}
