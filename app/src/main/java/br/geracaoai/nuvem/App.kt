package br.geracaoai.nuvem

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import br.geracaoai.nuvem.data.CloudIndex
import br.geracaoai.nuvem.data.DiagLog
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.work.Sched

class App : Application() {
    companion object {
        lateinit var instance: App
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        CloudIndex.init(this)
        DiagLog.init(this)
        val antes = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { DiagLog.log("TRAVOU", "${t.name}: ${e.javaClass.simpleName} ${e.message}") } catch (_: Throwable) {}
            antes?.uncaughtException(t, e)
        }
        DiagLog.log("APP", "app iniciou, versão ${BuildConfig.VERSION_NAME}")
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(Sched.CHANNEL, "Backup", NotificationManager.IMPORTANCE_LOW))
        Td.init(this)
        Sched.keepPeriodic(this)
    }
}
