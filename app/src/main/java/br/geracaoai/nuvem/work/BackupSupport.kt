package br.geracaoai.nuvem.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import br.geracaoai.nuvem.data.Checkpoint
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.data.Prefs2

class BackupReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        val a = i?.action ?: return
        when (a) {
            Backup.ACTION_PAUSE, Backup.ACTION_STOP -> Backup.stop(c)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                try { Prefs.init(c) } catch (_: Exception) {}
                if (Prefs.autoBackup && !Prefs2.backupPaused) {
                    Backup.schedule(c)
                    if (Checkpoint.shouldResume()) Backup.runNow(c)
                }
            }
        }
    }
}

object NetworkWatch {
    private var registered = false
    fun start(c: Context) {
        if (registered) return
        val app = c.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!Prefs.autoBackup || Prefs2.backupPaused) return
                    if (Backup.isWorkRunning(app)) return
                    if (!Checkpoint.shouldResume() && Prefs.lastError.isBlank()) return
                    val caps = try { cm.getNetworkCapabilities(network) } catch (_: Exception) { null } ?: return
                    val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val net = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    if (!net) return
                    if (Prefs.wifiOnly && !wifi) return
                    Backup.runNow(app)
                }
            })
            registered = true
        } catch (_: Exception) {}
    }
}
