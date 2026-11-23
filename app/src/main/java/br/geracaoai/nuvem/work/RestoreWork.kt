package br.geracaoai.nuvem.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import br.geracaoai.nuvem.R
import br.geracaoai.nuvem.data.CloudItem
import br.geracaoai.nuvem.data.Db
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.data.Repo
import br.geracaoai.nuvem.data.RestoreLogic
import br.geracaoai.nuvem.data.RestoreStore
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.ui.MainActivity
import kotlinx.coroutines.CancellationException

object Restore {
    const val UNIQUE = "nuvem_restore"
    const val CHANNEL = "restore"
    const val ACTION_CANCEL = "br.geracaoai.nuvem.RESTORE_CANCEL"
    const val NOTIF_ID = 43
    @Volatile var cancelled = false
      private set

    fun cancel(c: Context) {
        cancelled = true
        try { WorkManager.getInstance(c).cancelUniqueWork(UNIQUE) } catch (_: Exception) {}
        try {
            RestoreStore.load()
            RestoreStore.save(RestoreLogic.cancelQueue(RestoreStore.loadOr()))
        } catch (_: Exception) {}
        try { c.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) } catch (_: Exception) {}
        try { c.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID + 1) } catch (_: Exception) {}
    }


    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, "Restaurar", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progresso da restauração em segundo plano"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    fun isRunning(c: Context): Boolean = try {
        WorkManager.getInstance(c).getWorkInfosForUniqueWork(UNIQUE).get().any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    } catch (_: Exception) { false }

    fun enqueue(c: Context, items: List<CloudItem>) {
        if (items.isEmpty()) return
        cancelled = false
        RestoreStore.load()
        RestoreStore.enqueue(items.map { RestoreLogic.fromCloud(it) })
        start(c)
    }

    fun start(c: Context) {
        cancelled = false
        ensureChannel(c)
        val b = OneTimeWorkRequestBuilder<RestoreWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(c).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, b.build())
    }
}

class RestoreWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private fun notif(text: String, done: Int, total: Int): Notification {
        Restore.ensureChannel(applicationContext)
        val open = PendingIntent.getActivity(
            applicationContext, 8,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getBroadcast(
            applicationContext, 9,
            Intent(applicationContext, RestoreReceiver::class.java).setAction(Restore.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(applicationContext, Restore.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (total > 0) "Restaurando • $done de $total" else "Restaurando")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), done, total <= 0)
            .addAction(0, "Cancelar", cancel)
            .build()
    }

    override suspend fun getForegroundInfo() = ForegroundInfo(Restore.NOTIF_ID, notif("Preparando…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    override suspend fun doWork(): Result {
        try { setForeground(getForegroundInfo()) } catch (_: Exception) {}
        if (!Td.ready) { Td.init(applicationContext); if (!Td.awaitReady()) return Result.retry() }
        if (Restore.cancelled) return Result.success()
        RestoreStore.load()
        var list = RestoreStore.loadOr()
        val total = list.size
        var done = RestoreLogic.doneCount(list)
        while (true) {
            if (Restore.cancelled || isStopped) {
                list = RestoreLogic.cancelQueue(list)
                RestoreStore.save(list)
                break
            }
            val next = RestoreLogic.nextPending(list) ?: break
            list = RestoreLogic.markRunning(list, next.key)
            RestoreStore.save(list)
            setProgressAsync(workDataOf("done" to done, "total" to total, "name" to next.name))
            try { setForeground(ForegroundInfo(Restore.NOTIF_ID, notif(next.name, done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) } catch (_: Exception) {}
            val ci = Db.getCloud(next.chatId, next.msgId) ?: CloudItem(next.msgId, next.chatId, next.name, next.mime, next.size, next.taken, next.folder, next.path, "", null)
            if (Restore.cancelled || isStopped) {
                list = RestoreLogic.cancelQueue(list)
                RestoreStore.save(list)
                break
            }
            val ok = try { Repo.restore(applicationContext, ci) { p ->
                if (Restore.cancelled) throw CancellationException("cancel")
                try { setForegroundAsync(ForegroundInfo(Restore.NOTIF_ID, notif("${next.name} • ${(p * 100).toInt()}%", done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) } catch (_: Exception) {}
            } } catch (e: CancellationException) {
                list = RestoreLogic.cancelQueue(list)
                RestoreStore.save(list)
                if (Restore.cancelled || isStopped) break else throw e
            } catch (e: Exception) { false }
            list = if (ok) {
                done++
                RestoreStore.markKeyDone(next.chatId, next.msgId)
                RestoreLogic.markDone(list, next.key)
            } else RestoreLogic.markFailed(list, next.key, "Falha ao baixar ou salvar")
            RestoreStore.save(list)
        }
        val leftover = RestoreLogic.pendingCount(list)
        val failed = RestoreLogic.failedCount(list)
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        val end = NotificationCompat.Builder(applicationContext, Restore.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Restaurar")
            .setContentText(when {
                Restore.cancelled -> "Restauração cancelada. $done já ${if (done == 1) "estava pronto" else "estavam prontos"}."
                failed > 0 && leftover == 0 -> "Restaurados $done. $failed falharam e podem ser tentados de novo."
                leftover > 0 -> "Restaurados $done. Ainda faltam $leftover."
                else -> "Pronto! $done ${if (done == 1) "item restaurado" else "itens restaurados"}."
            })
            .setAutoCancel(true)
            .build()
        try { nm?.notify(Restore.NOTIF_ID + 1, end) } catch (_: Exception) {}
        return if (!Restore.cancelled && leftover > 0) Result.retry() else Result.success()
    }
}

class RestoreReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        if (i?.action == Restore.ACTION_CANCEL) Restore.cancel(c.applicationContext)
    }
}
