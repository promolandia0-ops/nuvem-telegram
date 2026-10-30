package br.geracaoai.nuvem.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import br.geracaoai.nuvem.R
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.shizuku.Shiz
import br.geracaoai.nuvem.tg.Td
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

object WatchedSync {
    const val PERIODIC = "watched_periodic"
    const val NOW = "watched_now"
    private fun cons() = Constraints.Builder().setRequiredNetworkType(if (Prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (!Prefs2.watchedEnabled || Prefs2.watchedPaused) { wm.cancelUniqueWork(PERIODIC); return }
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WatchedWorker>(15, TimeUnit.MINUTES).setConstraints(cons()).build())
    }
    fun runNow(c: Context, manual: Boolean = false) {
        if (manual) Prefs2.watchedPaused = false
        if (Prefs2.watchedPaused || !Prefs2.watchedEnabled) return
        WorkManager.getInstance(c).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<WatchedWorker>()
            .setConstraints(if (manual) Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build() else cons()).build())
        if (manual) schedule(c)
    }
    fun stop(c: Context) {
        Prefs2.watchedPaused = true
        val wm = WorkManager.getInstance(c); wm.cancelUniqueWork(NOW); wm.cancelUniqueWork(PERIODIC)
        Backup.cancelPendingUploads()
    }
}

class WatchedWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    private fun info(t: String, pct: Int) = ForegroundInfo(43, NotificationCompat.Builder(applicationContext, Backup.CHANNEL).setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("Assistidos → nuvem").setContentText(t).setOngoing(true).setOnlyAlertOnce(true).setProgress(100, pct, false).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    override suspend fun getForegroundInfo() = info("Procurando vídeos…", 0)

    override suspend fun doWork(): Result {
        if (Prefs2.watchedPaused || Prefs.chatId == 0L) return Result.success()
        Td.init(applicationContext)
        if (!Td.awaitReady()) return Result.retry()
        Shiz.awaitBinder()
        return try {
            val sent = Watched.sync(applicationContext) { pr ->
                setProgressAsync(workDataOf("done" to pr.done, "total" to pr.total, "name" to pr.name, "pct" to pr.pct))
                if (pr.total > 0) try { setForegroundAsync(info("${pr.done + 1}/${pr.total} • ${pr.name}", pr.pct)) } catch (_: Exception) {}
            }
            Prefs2.watchedError = ""
            Result.success(workDataOf("sent" to sent))
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { if (isStopped || Prefs2.watchedPaused) Result.success() else { Prefs2.watchedError = e.message ?: "erro"; Result.retry() } }
    }
}
