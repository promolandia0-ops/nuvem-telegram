package br.geracaoai.nuvem

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import br.geracaoai.nuvem.data.ActionLog
import br.geracaoai.nuvem.data.Db
import br.geracaoai.nuvem.data.FailStore
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.work.Backup

class App : Application() {
    companion object { lateinit var instance: App }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        br.geracaoai.nuvem.data.DiagLog.init(this)
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { br.geracaoai.nuvem.data.DiagLog.log("CRASH", "thread ${t.name}", e) } catch (_: Throwable) {}
            prevHandler?.uncaughtException(t, e)
        }
        br.geracaoai.nuvem.data.DiagLog.log("APP", "app iniciou")
        Db.init(this)
        ActionLog.load(); FailStore.load()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Backup.CHANNEL, "Backup", NotificationManager.IMPORTANCE_LOW))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(br.geracaoai.nuvem.play.PlaybackService.CHANNEL, "Player", NotificationManager.IMPORTANCE_LOW))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(br.geracaoai.nuvem.work.Restore.CHANNEL, "Restaurar", NotificationManager.IMPORTANCE_LOW))
        Td.init(this)
        Backup.schedule(this)
        br.geracaoai.nuvem.work.NetworkWatch.start(this)
        br.geracaoai.nuvem.work.WatchedSync.schedule(this)
        br.geracaoai.nuvem.data.RestoreStore.load()
        br.geracaoai.nuvem.work.MediaWatch.start(this)
    }
}
