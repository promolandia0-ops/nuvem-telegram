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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

object Backup {
    const val CHANNEL = "backup"
    const val PERIODIC = "backup_periodic"
    const val NOW = "backup_now"
    private val gate = AtomicBoolean(false)
    fun tryStart(): Boolean = gate.compareAndSet(false, true)
    fun end() { gate.set(false) }
    fun runningInProcess() = gate.get()

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(if (Prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(Prefs.chargingOnly).build()

    fun isWorkRunning(c: Context): Boolean {
        if (runningInProcess()) return true
        return try {
            val wm = WorkManager.getInstance(c)
            (wm.getWorkInfosForUniqueWork(NOW).get() + wm.getWorkInfosForUniqueWork(PERIODIC).get())
                .any { it.state == WorkInfo.State.RUNNING }
        } catch (_: Exception) { false }
    }

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (!Prefs.autoBackup || Prefs2.backupPaused) { wm.cancelUniqueWork(PERIODIC); return }
        val running = try { wm.getWorkInfosForUniqueWork(PERIODIC).get().any { it.state == WorkInfo.State.RUNNING } } catch (_: Exception) { false }
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            if (running) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS).setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
    }

    fun stop(c: Context) {
        Prefs2.backupPaused = true
        val wm = WorkManager.getInstance(c)
        wm.cancelUniqueWork(NOW); wm.cancelUniqueWork(PERIODIC)
        cancelPendingUploads()
    }

    fun resume(c: Context) { Prefs2.backupPaused = false; schedule(c); runNow(c, true) }

    fun cancelPendingUploads() {
        val chat = Prefs.chatId; if (chat == 0L) return
        val ids = Td.pendingSends(); if (ids.isEmpty()) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            for (id in ids) try {
                val m = Td.send(org.drinkless.tdlib.TdApi.GetMessage(chat, id))
                Repo.cancelSending(chat, id, Repo.fileOf(m)?.id ?: -1)
            } catch (_: Exception) {}
        }
    }

    fun runNow(c: Context, ignoreConstraints: Boolean = false, expedited: Boolean = false) {
        if (Prefs2.backupPaused) return
        if (isWorkRunning(c)) return
        val b = OneTimeWorkRequestBuilder<BackupWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        b.setConstraints(if (ignoreConstraints) Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build() else constraints())
        if (expedited) b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
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
        if (Prefs.chatId == 0L || (Prefs.folders.isEmpty() && Prefs.secretFolders.isEmpty()) || Prefs2.backupPaused) return Result.success()
        if (!Td.awaitReady()) return Result.retry()
        if (!Backup.tryStart()) return Result.success()
        try { setForeground(getForegroundInfo()) } catch (_: Exception) {}
        return try {
            Prefs.lastError = ""
            fun report(d: Int, total: Int, name: String, pct: Int) {
                setProgressAsync(workDataOf("done" to d, "total" to total, "name" to name, "pct" to pct))
                setForegroundAsync(ForegroundInfo(42, notif(name, d, total, pct), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            }
            report(0, 0, "Preparando o destino…", 0)
            val main = Repo.ensureDestination()
            var secretChat = 0L
            if (Prefs.secretFolders.isNotEmpty()) {
                try { secretChat = Repo.ensureSecretDestination().id } catch (e: Exception) {
                    if (isCancel(e)) throw e
                    Prefs.lastError = describeCreateError(e)
                }
            }
            report(0, 0, "Sincronizando…", 0)
            Repo.syncIndex { page -> report(0, 0, "Sincronizando… página $page", 0) }
            val max = Meta.maxSize(Td.isPremium.value)
            report(0, 0, "Lendo a galeria…", 0)
            val buckets = Prefs.folders + Prefs.secretFolders
            val todo = LocalMedia.all(applicationContext, buckets).filter { it.size in 1..max && File(it.path).canRead() }
            var done = 0; var sentPhotos = 0; var sentVideos = 0; var sentDocs = 0; var sentBytes = 0L
            val total = todo.size
            if (total == 0) {
                Prefs.lastError = if (buckets.isEmpty()) "Nenhuma pasta selecionada." else "Nenhum arquivo acessível nas pastas escolhidas. Confira a permissão de fotos e vídeos."
                Prefs.lastBackup = System.currentTimeMillis()
                return Result.success()
            }

            data class Target(val chatId: Long, val topic: String)
            val groups = LinkedHashMap<Target, ArrayList<Pair<LocalItem, String>>>()
            val missingOrig = LinkedHashMap<Target, ArrayList<Pair<LocalItem, String>>>()
            val seen = HashSet<String>()

            for (i in todo.sortedBy { it.taken }) {
                if (isStopped || Prefs2.backupPaused) break
                report(done + groups.values.sumOf { it.size }, total, "Verificando ${i.bucket.ifBlank { "álbum" }} • ${i.name}", 0)
                val h = Repo.localHash(i)
                val existing = Db.anyByHash(h, i.size)
                val secret = i.bucket in Prefs.secretFolders
                val chat = if (secret) {
                    if (secretChat == 0L) { done++; continue }
                    secretChat
                } else main.id
                val (dur, w, hgt) = try { Repo.mediaHints(i) } catch (_: Exception) { Triple(0L, 0, 0) }
                val topic = Classify.topicOf(i, dur, w, hgt, Prefs.smartSort)
                val target = Target(chat, topic)
                if (existing != null || Repo.alreadyOnCloud(h, i.size, i.name)) {
                    if (Prefs.originalPhotos && i.mime.startsWith("image/") && Db.originalByHash(chat, h) == null && Db.anyByHash(h)?.folder != ORIGINALS_FOLDER) {
                        missingOrig.getOrPut(target) { ArrayList() } += i to h
                    }
                    Dedup.claimHash(seen, h, i.size)
                    done++; report(done, total, i.name, 100); continue
                }
                if (!Dedup.claimHash(seen, h, i.size)) {
                    done++; report(done, total, i.name, 100); continue
                }
                groups.getOrPut(target) { ArrayList() } += i to h
            }

            val sem = Semaphore(3)
            coroutineScope {
                for ((target, pending) in groups) {
                    if (isStopped || Prefs2.backupPaused) break
                    launch {
                        sem.withPermit {
                            val topicId = Topics.idFor(target.chatId, target.topic)
                            val ordered = pending.sortedBy { it.first.taken }
                            for (chunk in Repo.chunksForAlbum(ordered)) {
                                if (isStopped || Prefs2.backupPaused) return@withPermit
                                coroutineContext.ensureActive()
                                var last = -1
                                Repo.uploadGroup(applicationContext, chunk, { idx, p ->
                                    val name = "${target.topic} • ${chunk[idx].first.name}"
                                    val pct = (p * 100).toInt()
                                    if (pct != last) { last = pct; report(done + idx, total, name, pct) }
                                }, target.chatId, topicId, target.topic)
                                synchronized(this@BackupWorker) {
                                    for ((item, _) in chunk) {
                                        when {
                                            item.mime.startsWith("image/") -> sentPhotos++
                                            item.mime.startsWith("video/") -> sentVideos++
                                            else -> sentDocs++
                                        }
                                        sentBytes += item.size
                                        done++
                                    }
                                }
                                report(done, total, chunk.last().first.name, 100)
                            }
                            if (!isStopped && !Prefs2.backupPaused) try { Repo.sendAlbumMarker(target.topic, target.chatId, topicId) } catch (_: Exception) {}
                            val leftovers = missingOrig.remove(target)
                            if (!leftovers.isNullOrEmpty() && Prefs.originalPhotos && !isStopped) {
                                try { Repo.uploadOriginalAlbum(applicationContext, leftovers, { _, _ -> }, target.chatId, topicId) } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
            if (!isStopped && !Prefs2.backupPaused) {
                for ((target, leftovers) in missingOrig) {
                    if (leftovers.isEmpty()) continue
                    try {
                        val topicId = Topics.idFor(target.chatId, target.topic)
                        Repo.uploadOriginalAlbum(applicationContext, leftovers, { _, _ -> }, target.chatId, topicId)
                    } catch (_: Exception) {}
                }
            }
            if (isStopped || Prefs2.backupPaused) return Result.success()
            val summary = backupSummary(sentPhotos, sentVideos, sentDocs, sentBytes)
            if (sentPhotos + sentVideos + sentDocs > 0) try { Repo.sendSummary(summary, main.id) } catch (_: Exception) {}
            try { Repo.pushIndex(main.id) } catch (_: Exception) {}
            if (secretChat != 0L) try { Repo.pushIndex(secretChat) } catch (_: Exception) {}
            Prefs.lastBackup = System.currentTimeMillis(); Prefs.lastError = ""
            Result.success(workDataOf("sent" to (sentPhotos + sentVideos + sentDocs), "summary" to summary))
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: TdException) {
            if (isStopped || Prefs2.backupPaused || isCancel(e)) return Result.success()
            Prefs.lastError = e.message ?: "erro"
            try { setProgress(workDataOf("name" to "Erro: ${Prefs.lastError}")) } catch (_: Exception) {}
            if (e.code == 429 || e.code >= 500) Result.retry() else Result.failure()
        } catch (e: Exception) {
            if (isStopped || Prefs2.backupPaused || isCancel(e)) return Result.success()
            Prefs.lastError = e.message ?: "erro"
            try { setProgress(workDataOf("name" to "Erro: ${Prefs.lastError}")) } catch (_: Exception) {}
            Result.failure()
        } finally {
            Backup.end()
        }
    }
}
