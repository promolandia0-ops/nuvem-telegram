package br.geracaoai.nuvem

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import br.geracaoai.nuvem.data.Db
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.work.Backup

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Db.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Backup.CHANNEL, "Backup", NotificationManager.IMPORTANCE_LOW))
        Td.init(this)
        Backup.schedule(this)
        br.geracaoai.nuvem.work.WatchedSync.schedule(this)
    }
}
