package br.geracaoai.nuvem.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import br.geracaoai.nuvem.R
import br.geracaoai.nuvem.data.DiagLog
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.shizuku.Shiz
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Excluir aplicativos em lote: fila salva no aparelho; com o Shizuku desinstala um por um em segundo plano,
 * sem pedir confirmação de cada um (continua mesmo saindo do app).
 */
object Uninstall {
    const val WORK = "uninstall_apps"
    private const val KEY = "uninstall_queue"
    data class Progress(val done: Int, val total: Int, val current: String, val failed: List<String>)
    val progress = MutableStateFlow<Progress?>(null)

    fun queue(): List<String> = (Prefs.raw.getString(KEY, "") ?: "").split('\n').filter { it.isNotBlank() }
    private fun save(l: List<String>) { Prefs.raw.edit().putString(KEY, l.joinToString("\n")).commit() }

    fun enqueue(c: Context, pkgs: List<String>) {
        save((queue() + pkgs).distinct())
        val req = OneTimeWorkRequestBuilder<UninstallWorker>().build()
        WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
    }
    fun cancel(c: Context) { save(emptyList()); WorkManager.getInstance(c).cancelUniqueWork(WORK); progress.value = null }
    fun pop(pkg: String) { save(queue() - pkg) }
}

class UninstallWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private fun info(text: String, done: Int, total: Int) = ForegroundInfo(Backup.NOTIF_ID + 3,
        NotificationCompat.Builder(applicationContext, Backup.CHANNEL).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (total > 0) "Excluindo apps • $done de $total" else "Excluindo apps")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setProgress(total.coerceAtLeast(1), done, total == 0).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    override suspend fun getForegroundInfo() = info("Preparando…", 0, 0)

    override suspend fun doWork(): Result {
        Backup.ensureChannel(applicationContext)
        try { setForeground(getForegroundInfo()) } catch (_: Exception) {}
        Shiz.awaitBinder()
        val svc = Shiz.service()
        if (svc == null) { DiagLog.log("APPS", "Shizuku indisponível; exclusão em lote parada"); Uninstall.progress.value = null; return Result.success() }
        val pm = applicationContext.packageManager
        val total = Uninstall.queue().size
        var done = 0
        val failed = ArrayList<String>()
        while (!isStopped) {
            val pkg = Uninstall.queue().firstOrNull() ?: break
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            Uninstall.progress.value = Uninstall.Progress(done, total, label, failed.toList())
            try { setForeground(info("Excluindo $label", done, total)) } catch (_: Exception) {}
            val out = try { svc.uninstall(pkg) } catch (e: Exception) { "Failure: ${e.message}" }
            if (!out.contains("Success")) { failed += label; DiagLog.log("APPS", "não excluiu $pkg: $out") } else DiagLog.log("APPS", "excluído: $pkg")
            Uninstall.pop(pkg)
            done++
        }
        Uninstall.progress.value = Uninstall.Progress(done, total, "", failed.toList())
        return Result.success()
    }
}
