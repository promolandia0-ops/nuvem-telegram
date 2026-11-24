package br.geracaoai.nuvem.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.*
import br.geracaoai.nuvem.R
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.tg.TdException
import br.geracaoai.nuvem.ui.MainActivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

object Backup {
    const val CHANNEL = "backup"
    const val PERIODIC = "backup_periodic"
    const val NOW = "backup_now"
    const val CONTINUE = "backup_continue"
    const val ACTION_PAUSE = "br.geracaoai.nuvem.BACKUP_PAUSE"
    const val ACTION_STOP = "br.geracaoai.nuvem.BACKUP_STOP"
    const val NOTIF_ID = 42
    private val gate = AtomicBoolean(false)
    fun tryStart(): Boolean = gate.compareAndSet(false, true)
    fun end() { gate.set(false) }
    fun runningInProcess() = gate.get()

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, "Backup", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progresso do backup em segundo plano"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(if (Prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(Prefs.chargingOnly).build()

    private fun uniqueNames() = listOf(NOW, PERIODIC, CONTINUE)

    fun isWorkRunning(c: Context): Boolean {
        if (runningInProcess()) return true
        return try {
            val wm = WorkManager.getInstance(c)
            uniqueNames().flatMap { wm.getWorkInfosForUniqueWork(it).get(2, TimeUnit.SECONDS) }
                .any { it.state == WorkInfo.State.RUNNING }
        } catch (_: Exception) { false }
    }

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (!Prefs.autoBackup || Prefs2.backupPaused) { wm.cancelUniqueWork(PERIODIC); return }
        val running = try { wm.getWorkInfosForUniqueWork(PERIODIC).get(2, TimeUnit.SECONDS).any { it.state == WorkInfo.State.RUNNING } } catch (_: Exception) { false }
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            if (running) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS).setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
    }

    fun stop(c: Context) {
        Prefs2.backupPaused = true
        val wm = WorkManager.getInstance(c)
        wm.cancelUniqueWork(NOW); wm.cancelUniqueWork(PERIODIC); wm.cancelUniqueWork(CONTINUE)
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
        // Toque do usuário (Iniciar/Sim/Retomar) tira a pausa e sempre começa.
        if (ignoreConstraints) {
            Prefs2.backupPaused = false
            gate.set(false) // trava presa de um run antigo: o toque do usuário solta e recomeça
        }
        if (Prefs2.backupPaused) return
        // Automático: não empilha se já roda. Manual (Iniciar): sempre começa, trocando um run preso.
        if (!ignoreConstraints && isWorkRunning(c)) return
        WorkManager.getInstance(c).cancelUniqueWork(CONTINUE)
        val b = OneTimeWorkRequestBuilder<BackupWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        b.setConstraints(if (ignoreConstraints) Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build() else constraints())
        if (expedited) b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(c).enqueueUniqueWork(NOW, if (ignoreConstraints) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, b.build())
    }

    /** Agenda a continuação depois de um timeout ou queda, sem rodar em paralelo. */
    fun continueSoon(c: Context, delayMin: Long = 1) {
        if (Prefs2.backupPaused || !Prefs.autoBackup) return
        val b = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay(delayMin.coerceAtLeast(1), TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setConstraints(constraints())
        WorkManager.getInstance(c).enqueueUniqueWork(CONTINUE, ExistingWorkPolicy.REPLACE, b.build())
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val startedElapsed = SystemClock.elapsedRealtime()
    private val keep = KeepAlive(ctx)

    private fun piFlags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun actionPi(action: String, req: Int): PendingIntent {
        val i = Intent(applicationContext, BackupReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(applicationContext, req, i, piFlags())
    }

    private fun openPi(): PendingIntent {
        val i = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(applicationContext, 7, i, piFlags())
    }

    private fun notif(text: String, done: Int, total: Int, pct: Int): Notification {
        val title = if (total > 0) "Backup no Telegram • $done de $total" else "Backup no Telegram"
        return NotificationCompat.Builder(applicationContext, Backup.CHANNEL).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openPi())
            .setProgress(100, pct.coerceIn(0, 100), total == 0)
            .addAction(0, "Pausar", actionPi(Backup.ACTION_PAUSE, 11))
            .addAction(0, "Parar", actionPi(Backup.ACTION_STOP, 12))
            .build()
    }

    override suspend fun getForegroundInfo() = ForegroundInfo(Backup.NOTIF_ID, notif("Preparando…", 0, 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private suspend fun startForegroundSafe() {
        Backup.ensureChannel(applicationContext)
        var last: Exception? = null
        repeat(3) { i ->
            try {
                setForeground(ForegroundInfo(Backup.NOTIF_ID, notif("Preparando…", 0, 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
                return
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                last = e
                if (isFgsTimeout(e)) throw e
                delay(400L * (i + 1))
            }
        }
        if (last != null) Prefs.lastError = "Não deu pra mostrar a notificação do backup. Permita notificações pra ele não parar em segundo plano."
    }

    private fun isFgsTimeout(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) {
            val n = c.javaClass.simpleName
            if (n == "ForegroundServiceTimeoutException" || n.contains("ForegroundServiceStartNotAllowed")) return true
            if (c.message?.contains("timed out", true) == true && c.message?.contains("foreground", true) == true) return true
            c = c.cause
        }
        return false
    }

    private fun nearTimeout() = StallPolicy.nearTimeout(startedElapsed, SystemClock.elapsedRealtime())

    private sealed class Ev {
        data class Ready(val item: LocalItem, val hash: String) : Ev()
        data class Skip(val item: LocalItem, val hash: String, val needOrig: Boolean) : Ev()
    }

    override suspend fun doWork(): Result {
        Td.init(applicationContext)
        keep.acquire()
        if (Prefs2.backupPaused) { keep.release(); return Result.success() }
        if (Prefs.chatId == 0L || (!Prefs.backupAll && Prefs.folders.isEmpty() && Prefs.secretFolders.isEmpty())) {
            Prefs.lastError = "Escolha o chat do Telegram em Ajustes para o backup começar."
            keep.release(); return Result.success()
        }
        if (!Td.awaitReady()) {
            Prefs.lastError = "Telegram ainda não conectou. Tentando de novo."
            keep.release(); Backup.continueSoon(applicationContext, 2); return Result.retry()
        }
        if (!Backup.tryStart()) {
            Prefs.lastError = "Outro backup ainda está terminando. Começa logo em seguida."
            keep.release(); Backup.continueSoon(applicationContext, 1); return Result.success()
        }
        return try {
            startForegroundSafe()
            runBackup()
        } catch (e: java.util.concurrent.CancellationException) {
            if (Checkpoint.shouldResume() && !Prefs2.backupPaused) Backup.continueSoon(applicationContext, 1)
            throw e
        } catch (e: CancellationException) {
            if (Checkpoint.shouldResume() && !Prefs2.backupPaused) Backup.continueSoon(applicationContext, 1)
            throw e
        } catch (e: Exception) {
            if (isStopped || Prefs2.backupPaused || isCancel(e)) return Result.success()
            if (isFgsTimeout(e) || nearTimeout()) {
                Backup.continueSoon(applicationContext, 1)
                return Result.success()
            }
            Prefs.lastError = e.message ?: "erro"
            try { setProgress(workDataOf("name" to "Erro: ${Prefs.lastError}")) } catch (_: Exception) {}
            Backup.continueSoon(applicationContext, 2)
            if (e is TdException && (e.code == 429 || e.code == 408 || e.code >= 500)) Result.retry() else Result.retry()
        } finally {
            keep.release()
            Backup.end()
        }
    }

    private suspend fun runBackup(): Result {
        Prefs.lastError = ""
        fun report(d: Int, total: Int, name: String, pct: Int) {
            setProgressAsync(workDataOf("done" to d, "total" to total, "name" to name, "pct" to pct))
            try {
                setForegroundAsync(ForegroundInfo(Backup.NOTIF_ID, notif(name, d, total, pct), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            } catch (_: Exception) {}
        }
        if (!Td.ready) { Td.init(applicationContext); if (!Td.awaitReady()) return Result.retry() }
        report(0, 0, Checkpoint.load()?.label?.takeIf { it.isNotBlank() } ?: "Preparando o destino…", 0)
        val main = Repo.ensureDestination()
        var secretChat = 0L
        if (Prefs.secretFolders.isNotEmpty()) {
            try { secretChat = Repo.ensureSecretDestination().id } catch (e: Exception) {
                if (isCancel(e)) throw e
                Prefs.lastError = describeCreateError(e)
            }
        }
        report(0, 0, "Conferindo o que está no Telegram…", 0)
        try { Repo.reconcileCloud() { done, tot -> report(done, tot, "Conferindo o Telegram… $done/$tot", 0) } } catch (_: Exception) {}

        val max = Meta.maxSize(Td.isPremium.value)
        val allByBucket: Map<String, List<LocalItem>> by lazy { MediaScan.allAnywhere(applicationContext).groupBy { it.bucket } }
        val selected = if (Prefs.backupAll) allByBucket.keys.toSet() else Prefs.folders + Prefs.secretFolders
        report(0, 0, "Procurando arquivos nas pastas…", 0)
        val folderCache = HashMap<String, List<LocalItem>>()
        fun loadFolder(name: String): List<LocalItem> = folderCache.getOrPut(name) {
            val prio = RestoreStore.priorityPaths
            val items = (if (Prefs.backupAll) allByBucket[name].orEmpty() else LocalMedia.scanAndWalk(applicationContext, name)).filter { it.size in 1..max && (File(it.path).canRead() || it.mediaId > 0 || AllFilesAccess.granted()) }
            if (prio.isEmpty()) items
            else items.sortedWith(compareBy<LocalItem> { if (it.path in prio) 0 else 1 }.thenBy { it.taken }.thenBy { it.mediaId })
        }
        val counts = withContext(Dispatchers.IO) { selected.associateWith { loadFolder(it).size } }
        val folders = FolderPlan.order(selected.filter { (counts[it] ?: 0) > 0 })
        val total = folders.sumOf { counts[it] ?: 0 }
        if (folders.isEmpty() || total == 0) {
            Prefs.lastError = if (selected.isEmpty()) "Nenhuma pasta selecionada." else "Nenhum arquivo acessível nas pastas escolhidas. Confira a permissão de fotos e vídeos."
            Prefs.lastBackup = System.currentTimeMillis()
            Checkpoint.clear()
            return Result.success()
        }

        var done = 0; var sentPhotos = 0; var sentVideos = 0; var sentDocs = 0; var sentBytes = 0L
        val seen = HashSet<String>()
        val deferred = ArrayList<DeferredUpload>()
        val runStarted = Checkpoint.load()?.startedAt?.takeIf { it > 0 } ?: System.currentTimeMillis()
        val cloudIndex = try {
            Db.allCloudMany(Prefs.allKnownChats()).map { CloudIdentity(it.path, it.name, it.size, it.hash) }
        } catch (_: Exception) { emptyList() }

        fun isDocPair(p: Pair<LocalItem, String>): Boolean {
            val mime = p.first.mime.ifBlank { Meta.mimeFromName(p.first.name) }
            return Meta.sendKind(mime, p.first.size) == SendKind.DOCUMENT
        }

        suspend fun maybeYieldForTimeout(folder: String, folderDone: Int, folderTotal: Int): Boolean {
            if (!nearTimeout()) return false
            Checkpoint.mark(folder, done, total, startedAt = runStarted)
            report(done, total, "Continuando daqui a pouco (limite do Android)…", 0)
            Backup.continueSoon(applicationContext, 1)
            return true
        }

        var splitForTimeout = false
        coroutineScope {
            var nextList = async(Dispatchers.IO) { if (folders.isNotEmpty()) loadFolder(folders[0]) else emptyList() }
            var prehashJob: kotlinx.coroutines.Job? = null

            folderLoop@ for ((idx, folder) in folders.withIndex()) {
                if (isStopped || Prefs2.backupPaused) break
                if (maybeYieldForTimeout(folder, 0, counts[folder] ?: 0)) { splitForTimeout = true; break }
                if (!Td.ready) { Td.init(applicationContext); Td.awaitReady(15_000) }
                keep.acquire()
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
                Checkpoint.mark(album, done, total, startedAt = runStarted)

                val evs = Channel<Ev>(32)
                val producer = launch(Dispatchers.IO) {
                    try {
                        for (i in items) {
                            if (isStopped || Prefs2.backupPaused) break
                            coroutineContext.ensureActive()
                            val h = try { Repo.localHash(i) } catch (_: Exception) { "" }
                            val existing = BackupDiff.isUploaded(i.path, i.size, h, cloudIndex) ||
                                (h.isNotBlank() && !Dedup.claimHash(seen, h, i.size))
                            if (existing || h.isBlank()) {
                                val needOrig = Prefs.originalPhotos && i.mime.startsWith("image/") &&
                                    h.isNotBlank() && Db.originalByHash(chat, h) == null && Db.anyByHash(h)?.folder != ORIGINALS_FOLDER &&
                                    BackupDiff.isUploaded(i.path, i.size, h, cloudIndex)
                                evs.send(Ev.Skip(i, h, needOrig))
                            } else evs.send(Ev.Ready(i, h))
                        }
                    } finally { evs.close() }
                }

                val buf = ArrayList<Pair<LocalItem, String>>()
                val missingOrig = ArrayList<Pair<LocalItem, String>>()
                var sentInFolder = 0

                suspend fun sendChunks(chunks: List<List<Pair<LocalItem, String>>>): Boolean {
                    for (chunk in chunks) {
                        if (isStopped || Prefs2.backupPaused) return false
                        if (maybeYieldForTimeout(album, folderDone, folderTotal)) return false
                        coroutineContext.ensureActive()
                        keep.acquire()
                        var last = -1
                        var tries = 0
                        var result = UploadResult()
                        while (true) {
                            try {
                                result = Repo.uploadGroup(applicationContext, chunk, { i, p ->
                                    val pct = (p * 100).toInt()
                                    if (pct != last) {
                                        last = pct
                                        report(done + i, total, "$album • ${folderDone + i} de $folderTotal • ${chunk[i].first.name}", pct)
                                    }
                                }, chat, topicId, album)
                                break
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                if (isCancel(e) || Prefs2.backupPaused || isStopped) throw e
                                if (ImageProcess.isFailed(e)) {
                                    result = UploadResult(deferred = chunk.map { (i, h) -> DeferredUpload(i, h, e.message ?: "Falha no envio", album, chat, topicId) })
                                    break
                                }
                                if (!StallPolicy.shouldRetry(tries)) {
                                    result = UploadResult(deferred = chunk.map { (i, h) -> DeferredUpload(i, h, e.message ?: "Falha no envio", album, chat, topicId) })
                                    break
                                }
                                tries++
                                delay(StallPolicy.backoffMs(tries))
                            }
                        }
                        deferred += result.deferred
                        val sentHashes = result.sent.map { it.second }.toHashSet()
                        for ((item, h) in chunk) {
                            if (h in sentHashes) {
                                when {
                                    item.mime.startsWith("image/") -> sentPhotos++
                                    item.mime.startsWith("video/") -> sentVideos++
                                    else -> sentDocs++
                                }
                                sentBytes += item.size
                                sentInFolder++
                                if (h.isNotBlank()) FailStore.remove(h)
                            }
                            done++; folderDone++
                            Checkpoint.mark(album, done, total, Checkpoint.itemKey(item.path, item.size, item.mtime), runStarted)
                        }
                        report(done, total, "$album • $folderDone de $folderTotal • ${chunk.last().first.name}", 100)
                    }
                    return true
                }

                for (ev in evs) {
                    if (isStopped || Prefs2.backupPaused || splitForTimeout) break
                    when (ev) {
                        is Ev.Skip -> {
                            if (ev.needOrig) missingOrig += ev.item to ev.hash
                            Dedup.claimHash(seen, ev.hash, ev.item.size)
                            done++; folderDone++
                            Checkpoint.mark(album, done, total, Checkpoint.itemKey(ev.item.path, ev.item.size, ev.item.mtime), runStarted)
                            report(done, total, "$album • $folderDone de $folderTotal • ${ev.item.name}", 100)
                        }
                        is Ev.Ready -> {
                            buf += ev.item to ev.hash
                            if (!sendChunks(FolderPlan.pullReadyChunks(buf, flush = false, isDoc = ::isDocPair, max = 10))) {
                                splitForTimeout = true; break
                            }
                        }
                    }
                }
                producer.join()
                if (!isStopped && !Prefs2.backupPaused && !splitForTimeout) {
                    if (!sendChunks(FolderPlan.pullReadyChunks(buf, flush = true, isDoc = ::isDocPair, max = 10))) {
                        splitForTimeout = true
                    } else {
                        if (Prefs.originalPhotos && missingOrig.isNotEmpty()) {
                            try { Repo.uploadOriginalAlbum(applicationContext, missingOrig, { _, _ -> }, chat, topicId) } catch (_: Exception) {}
                        }
                        if (sentInFolder > 0) {
                            try { Repo.sendAlbumMarker(album, chat, topicId) } catch (_: Exception) {}
                            report(done, total, "📁 Álbum $album", 100)
                        }
                    }
                }
            }
            prehashJob?.cancel()
        }

        if (splitForTimeout) return Result.success()
        if (isStopped || Prefs2.backupPaused) {
            Checkpoint.mark(Checkpoint.load()?.folder ?: "", done, total, startedAt = runStarted)
            return Result.success()
        }
        if (deferred.isNotEmpty()) {
            report(done, total, "Tentando de novo ${deferred.size} que falharam…", 0)
            val still = try {
                Repo.retryDeferred(applicationContext, deferred) { i, n, name ->
                    report(done, total, "Tentando de novo $i de $n • $name", ((i * 100f) / n).toInt())
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (isCancel(e) || Prefs2.backupPaused || isStopped) throw e
                deferred
            }
            val recovered = deferred.filter { d -> still.none { it.hash == d.hash && it.item.path == d.item.path } }
            for (d in recovered) {
                when {
                    d.item.mime.startsWith("image/") -> sentPhotos++
                    d.item.mime.startsWith("video/") -> sentVideos++
                    else -> sentDocs++
                }
                sentBytes += d.item.size
                FailStore.remove(d.hash)
            }
            if (still.isNotEmpty()) FailStore.addAll(still.map { FailStore.fromDeferred(it, 2) })
            else deferred.forEach { FailStore.remove(it.hash) }
        }
        val leftover = FailStore.items.value
        val summary = backupSummary(sentPhotos, sentVideos, sentDocs, sentBytes)
        if (sentPhotos + sentVideos + sentDocs > 0) try { Repo.sendSummary(summary, main.id) } catch (_: Exception) {}
        try { Repo.pushIndex(main.id) } catch (_: Exception) {}
        if (secretChat != 0L) try { Repo.pushIndex(secretChat) } catch (_: Exception) {}
        Prefs.lastBackup = System.currentTimeMillis()
        Prefs.lastError = if (leftover.isNotEmpty()) "Alguns arquivos não foram enviados. Veja Falharam." else ""
        RestoreStore.priorityPaths = emptySet()
        Checkpoint.clear()
        try { FreeAsk.check(applicationContext) } catch (_: Exception) {}
        return Result.success(workDataOf("sent" to (sentPhotos + sentVideos + sentDocs), "summary" to summary))
    }
}
