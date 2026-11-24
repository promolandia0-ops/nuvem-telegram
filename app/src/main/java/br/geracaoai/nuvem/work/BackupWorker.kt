package br.geracaoai.nuvem.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import br.geracaoai.nuvem.data.Prefs
import kotlinx.coroutines.CancellationException

/** Roda o backup em segundo plano, mesmo com o app fechado. */
class BackupWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = Sched.foregroundInfo(ctx)

    override suspend fun doWork(): Result {
        if (Prefs.backupPaused) return Result.success()
        try {
            setForeground(getForegroundInfo())
        } catch (_: Exception) {
            // Sem permissão de serviço em primeiro plano nesse momento: segue sem notificação fixa.
        }
        return try {
            Engine.run(ctx)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
