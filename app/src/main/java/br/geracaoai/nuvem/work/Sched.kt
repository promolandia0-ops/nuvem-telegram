package br.geracaoai.nuvem.work

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import br.geracaoai.nuvem.R
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.ui.MainActivity
import java.util.concurrent.TimeUnit

/** Agenda o backup no WorkManager e mostra a notificação de progresso. */
object Sched {
    const val CHANNEL = "backup"
    const val NOW = "nuvem-agora"
    const val AUTO = "nuvem-auto"
    const val NOTIF_ID = 41

    private fun restricoes(manual: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (!manual && Prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /** Botão Fazer backup de tudo: começa já. A regra de Wi-Fi vale só para o automático. */
    fun startNow(c: Context) {
        Prefs.backupPaused = false
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(restricoes(manual = true))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
        Engine.markQueued()
    }

    fun stop(c: Context) {
        Engine.requestStop()
        WorkManager.getInstance(c).cancelUniqueWork(NOW)
    }

    /** Backup automático de tempos em tempos, ou desligado. */
    fun keepPeriodic(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (!Prefs.autoBackup) {
            wm.cancelUniqueWork(AUTO)
            return
        }
        val req = PeriodicWorkRequestBuilder<BackupWorker>(2, TimeUnit.HOURS)
            .setConstraints(restricoes(manual = false))
            .build()
        wm.enqueueUniquePeriodicWork(AUTO, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    private fun montar(c: Context, titulo: String, texto: String, feito: Int, total: Int): Notification =
        NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setContentIntent(
                PendingIntent.getActivity(
                    c,
                    0,
                    Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(total, feito, total == 0)
            .build()

    fun foregroundInfo(c: Context): ForegroundInfo = ForegroundInfo(
        NOTIF_ID,
        montar(c, "Nuvem", "Backup em andamento…", 0, 0),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    fun postProgress(c: Context, s: BackupState) {
        val texto = if (s.total > 0) "${s.done} de ${s.total} · ${s.current}" else s.message
        val titulo = if (s.paused) "Nuvem: pausado" else "Nuvem: backup"
        try {
            NotificationManagerCompat.from(c).notify(NOTIF_ID, montar(c, titulo, texto, s.done, s.total))
        } catch (_: Throwable) {
        }
    }

    fun clearProgress(c: Context) {
        try {
            NotificationManagerCompat.from(c).cancel(NOTIF_ID)
        } catch (_: Throwable) {
        }
    }
}
