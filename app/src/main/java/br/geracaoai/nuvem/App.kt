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
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Db.init(this)
        // leituras de registros fora da thread da tela (antes travavam a abertura e geravam "não está respondendo")
        Thread { try { ActionLog.load(); FailStore.load(); br.geracaoai.nuvem.data.RestoreStore.load() } catch (_: Exception) {} }.start()
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
        br.geracaoai.nuvem.work.MediaWatch.start(this)
    }
}
