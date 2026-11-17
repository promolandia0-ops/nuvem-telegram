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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private sealed class Ev {
        data class Ready(val item: LocalItem, val hash: String) : Ev()
        data class Skip(val item: LocalItem, val hash: String, val needOrig: Boolean) : Ev()
    }

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
            report(0, 0, "Conferindo o que está no Telegram…", 0)
            try { Repo.reconcileCloud(force = true) { done, tot -> report(done, tot, "Conferindo o Telegram… $done/$tot", 0) } } catch (_: Exception) {}

            val max = Meta.maxSize(Td.isPremium.value)
            val selected = Prefs.folders + Prefs.secretFolders
            report(0, 0, "Lendo pastas…", 0)
            val counts = withContext(Dispatchers.IO) { LocalMedia.countByBucket(applicationContext, selected) }
            val folders = FolderPlan.order(selected.filter { (counts[it] ?: 0) > 0 })
            val total = folders.sumOf { counts[it] ?: 0 }
            if (folders.isEmpty() || total == 0) {
                Prefs.lastError = if (selected.isEmpty()) "Nenhuma pasta selecionada." else "Nenhum arquivo acessível nas pastas escolhidas. Confira a permissão de fotos e vídeos."
                Prefs.lastBackup = System.currentTimeMillis()
                return Result.success()
            }

            var done = 0; var sentPhotos = 0; var sentVideos = 0; var sentDocs = 0; var sentBytes = 0L
            val seen = HashSet<String>()

            fun loadFolder(name: String): List<LocalItem> =
                LocalMedia.inBucket(applicationContext, name).filter { it.size in 1..max && File(it.path).canRead() }

            fun isDocPair(p: Pair<LocalItem, String>): Boolean {
                val mime = p.first.mime.ifBlank { Meta.mimeFromName(p.first.name) }
                return Meta.sendKind(mime, p.first.size) == SendKind.DOCUMENT
            }

            coroutineScope {
                var nextList = async(Dispatchers.IO) { if (folders.isNotEmpty()) loadFolder(folders[0]) else emptyList() }
                var prehashJob: kotlinx.coroutines.Job? = null

                for ((idx, folder) in folders.withIndex()) {
                    if (isStopped || Prefs2.backupPaused) break
                    val album = FolderPlan.albumName(folder)
                    val items = nextList.await()
                    val nextName = folders.getOrNull(idx + 1)
                    nextList = async(Dispatchers.IO) { if (nextName != null) loadFolder(nextName) else emptyList() }
                    prehashJob?.cancel()
                    if (nextName != null) {
                        val upcoming = nextList
                        prehashJob = launch(Dispatchers.IO) {
                            val listed = try { upcoming.await() } catch (_: Exception) { emptyList() }
                            for (i in listed) {
                                if (isStopped) break
                                coroutineContext.ensureActive()
                                try { Repo.localHash(i) } catch (_: Exception) {}
                            }
                        }
                    }

                    val secret = folder in Prefs.secretFolders
                    val chat = if (secret) {
                        if (secretChat == 0L) {
                            done += items.size
                            report(done, total, "$album • pasta secreta indisponível", 0)
                            continue
                        }
                        secretChat
                    } else main.id
                    val topicId = Topics.idFor(chat, album)
                    val folderTotal = items.size
                    var folderDone = 0

                    val evs = Channel<Ev>(32)
                    val producer = launch(Dispatchers.IO) {
                        try {
                            for (i in items) {
                                if (isStopped || Prefs2.backupPaused) break
                                coroutineContext.ensureActive()
                                val h = try { Repo.localHash(i) } catch (_: Exception) { "" }
                                val existing = h.isNotBlank() && (Repo.alreadyOnCloud(h, i.size, i.name) || !Dedup.claimHash(seen, h, i.size))
                                if (existing || h.isBlank()) {
                                    val needOrig = Prefs.originalPhotos && i.mime.startsWith("image/") &&
                                        h.isNotBlank() && Db.originalByHash(chat, h) == null && Db.anyByHash(h)?.folder != ORIGINALS_FOLDER &&
                                        Repo.alreadyOnCloud(h, i.size, i.name)
                                    evs.send(Ev.Skip(i, h, needOrig))
                                } else evs.send(Ev.Ready(i, h))
                            }
                        } finally { evs.close() }
                    }

                    val buf = ArrayList<Pair<LocalItem, String>>()
                    val missingOrig = ArrayList<Pair<LocalItem, String>>()
                    var sentInFolder = 0

                    suspend fun sendChunks(chunks: List<List<Pair<LocalItem, String>>>) {
                        for (chunk in chunks) {
                            if (isStopped || Prefs2.backupPaused) return
                            coroutineContext.ensureActive()
                            var last = -1
                            Repo.uploadGroup(applicationContext, chunk, { i, p ->
                                val pct = (p * 100).toInt()
                                if (pct != last) {
                                    last = pct
                                    report(done + i, total, "$album • ${folderDone + i}/$folderTotal • ${chunk[i].first.name}", pct)
                                }
                            }, chat, topicId, album)
                            for ((item, _) in chunk) {
                                when {
                                    item.mime.startsWith("image/") -> sentPhotos++
                                    item.mime.startsWith("video/") -> sentVideos++
                                    else -> sentDocs++
                                }
                                sentBytes += item.size
                                done++; folderDone++; sentInFolder++
                            }
                            report(done, total, "$album • $folderDone/$folderTotal • ${chunk.last().first.name}", 100)
                        }
                    }

                    for (ev in evs) {
                        if (isStopped || Prefs2.backupPaused) break
                        when (ev) {
                            is Ev.Skip -> {
                                if (ev.needOrig) missingOrig += ev.item to ev.hash
                                Dedup.claimHash(seen, ev.hash, ev.item.size)
                                done++; folderDone++
                                report(done, total, "$album • $folderDone/$folderTotal • ${ev.item.name}", 100)
                            }
                            is Ev.Ready -> {
                                buf += ev.item to ev.hash
                                sendChunks(FolderPlan.pullReadyChunks(buf, flush = false, isDoc = ::isDocPair, max = 10))
                            }
                        }
                    }
                    producer.join()
                    if (!isStopped && !Prefs2.backupPaused) {
                        sendChunks(FolderPlan.pullReadyChunks(buf, flush = true, isDoc = ::isDocPair, max = 10))
                        if (Prefs.originalPhotos && missingOrig.isNotEmpty()) {
                            try { Repo.uploadOriginalAlbum(applicationContext, missingOrig, { _, _ -> }, chat, topicId) } catch (_: Exception) {}
                        }
                        if (sentInFolder > 0) {
                            try { Repo.sendAlbumMarker(album, chat, topicId) } catch (_: Exception) {}
                            report(done, total, "📁 Álbum $album", 100)
                        }
                    }
                }
                prehashJob?.cancel()
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
