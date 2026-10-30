package br.geracaoai.nuvem.work

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import br.geracaoai.nuvem.R
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.tg.TdException
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.util.concurrent.TimeUnit

object Backup {
    const val CHANNEL = "backup"
    const val PERIODIC = "backup_periodic"
    const val NOW = "backup_now"

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(if (Prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(Prefs.chargingOnly).build()

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (!Prefs.autoBackup || Prefs2.backupPaused) { wm.cancelUniqueWork(PERIODIC); return }
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS).setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
    }

    /** Botão "Parar": pausa de verdade. Cancela os workers (a corrotina recebe CancellationException e
     *  o upload em andamento é abortado no TDLib) e não recomeça até o usuário tocar em "Retomar". */
    fun stop(c: Context) {
        Prefs2.backupPaused = true
        val wm = WorkManager.getInstance(c)
        wm.cancelUniqueWork(NOW); wm.cancelUniqueWork(PERIODIC)
        cancelPendingUploads()
    }

    fun resume(c: Context) { Prefs2.backupPaused = false; schedule(c); runNow(c, true) }

    /** Rede de segurança: aborta qualquer envio pendente no TDLib (mesmo se o worker já tiver morrido). */
    fun cancelPendingUploads() {
        val chat = Prefs.chatId; if (chat == 0L) return
        val ids = Td.pendingSends(); if (ids.isEmpty()) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            for (id in ids) try {
                val m = Td.send(org.drinkless.tdlib.TdApi.GetMessage(chat, id))
                Repo.cancelSending(chat, id, Repo.docOf(m)?.document?.id ?: -1)
            } catch (_: Exception) {}
        }
    }

    fun runNow(c: Context, ignoreConstraints: Boolean = false) {
        if (Prefs2.backupPaused) return
        val b = OneTimeWorkRequestBuilder<BackupWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        b.setConstraints(if (ignoreConstraints) Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build() else constraints())
        WorkManager.getInstance(c).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, b.build())
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private fun notif(text: String, done: Int, total: Int, pct: Int): Notification =
        NotificationCompat.Builder(applicationContext, Backup.CHANNEL).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Backup no Telegram • $done/$total").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, pct, total == 0).build()

    override suspend fun getForegroundInfo() = ForegroundInfo(42, notif("Preparando…", 0, 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    override suspend fun doWork(): Result {
        Td.init(applicationContext)
        if (Prefs.chatId == 0L || Prefs.folders.isEmpty() || Prefs2.backupPaused) return Result.success()
        if (!Td.awaitReady()) return Result.retry()
        try { setForeground(getForegroundInfo()) } catch (_: Exception) {}
        return try {
            Repo.syncIndex()
            val chat = Prefs.chatId
            val max = Meta.maxSize(Td.isPremium.value)
            val todo = LocalMedia.all(applicationContext, Prefs.folders).filter { it.size in 1..max && File(it.path).canRead() }
            var done = 0; var sent = 0
            val total = todo.size
            for (i in todo) {
                if (isStopped || Prefs2.backupPaused) break
                coroutineContext.ensureActive()
                val h = Repo.localHash(i)
                if (Db.cloudByHash(chat, h)?.size != i.size) {
                    var last = -1
                    Repo.upload(applicationContext, i, h) { p ->
                        val pct = (p * 100).toInt()
                        if (pct != last) { last = pct
                            setProgressAsync(workDataOf("done" to done, "total" to total, "name" to i.name, "pct" to pct))
                            setForegroundAsync(ForegroundInfo(42, notif(i.name, done, total, pct), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) }
                    }
                    sent++
                }
                done++
                setProgress(workDataOf("done" to done, "total" to total, "name" to i.name, "pct" to 100))
            }
            if (isStopped || Prefs2.backupPaused) return Result.success()
            Prefs.lastBackup = System.currentTimeMillis(); Prefs.lastError = ""
            Result.success(workDataOf("sent" to sent))
        } catch (e: CancellationException) { throw e
        } catch (e: TdException) {
            if (isStopped || Prefs2.backupPaused) return Result.success()
            Prefs.lastError = e.message ?: "erro"
            if (e.code == 429 || e.code >= 500 || e.code == 0) Result.retry() else Result.failure()
        } catch (e: Exception) { if (isStopped || Prefs2.backupPaused) return Result.success(); Prefs.lastError = e.message ?: "erro"; Result.retry() }
    }
}
