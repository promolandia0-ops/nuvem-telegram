package br.geracaoai.nuvem.mega

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.*
import br.geracaoai.nuvem.R
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.work.Backup
import br.geracaoai.nuvem.work.KeepAlive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Sessão do MEGA e o que já foi importado (só sessão e chave; a senha nunca é guardada). */
object MegaStore {
    private val p get() = Prefs.raw
    var email: String get() = p.getString("mega_email", "")!!; set(v) = p.edit().putString("mega_email", v).apply()
    var sid: String get() = p.getString("mega_sid", "")!!; set(v) = p.edit().putString("mega_sid", v).apply()
    var key: String get() = p.getString("mega_key", "")!!; set(v) = p.edit().putString("mega_key", v).apply()
    var user: String get() = p.getString("mega_user", "")!!; set(v) = p.edit().putString("mega_user", v).apply()
    val loggedIn get() = sid.isNotBlank() && key.isNotBlank()
    /** handle do MEGA -> hash do arquivo já salvo no Telegram. */
    fun done(): Map<String, String> = try {
        val j = org.json.JSONObject(p.getString("mega_done", "{}") ?: "{}")
        j.keys().asSequence().associateWith { j.optString(it) }
    } catch (_: Exception) { emptyMap() }
    fun markDone(handle: String, hash: String) {
        val j = try { org.json.JSONObject(p.getString("mega_done", "{}") ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
        j.put(handle, hash); p.edit().putString("mega_done", j.toString()).commit()
    }
    fun forget(handles: Collection<String>) {
        val j = try { org.json.JSONObject(p.getString("mega_done", "{}") ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
        handles.forEach { j.remove(it) }; p.edit().putString("mega_done", j.toString()).commit()
    }
    /** Pastas escolhidas para mandar ("Fotos", "Vídeos/2023"…). Vazio = tudo. */
    var folders: Set<String> get() = p.getStringSet("mega_folders", emptySet())!!; set(v) = p.edit().putStringSet("mega_folders", v).apply()
    fun wanted(path: String): Boolean {
        val f = folders; if (f.isEmpty()) return true
        return f.any { path == it || path.startsWith("$it/") }
    }
    var lastError: String get() = p.getString("mega_err", "")!!; set(v) = p.edit().putString("mega_err", v).apply()

    fun client() = MegaClient(sid, MegaClient.b64d(key), user)
    fun save(c: MegaClient, mail: String) { email = mail; sid = c.sid; key = MegaClient.b64e(c.masterKey); user = c.userHandle }
    fun logout() { sid = ""; key = ""; user = "" }

    /** Lista viva (para a tela) depois de ler a conta. */
    val nodes = MutableStateFlow<List<MegaNode>?>(null)

    /** Arquivos fora da lixeira, com o caminho de pastas ("Fotos/2023"). */
    fun files(all: List<MegaNode>): List<Pair<MegaNode, String>> {
        val byH = all.associateBy { it.handle }
        fun path(n: MegaNode): String? {
            val parts = ArrayList<String>()
            var cur: MegaNode? = byH[n.parent]
            var guard = 0
            while (cur != null && guard++ < 64) {
                when (cur.type) { 2 -> return parts.reversed().joinToString("/"); 3, 4 -> return null }
                parts += cur.name
                cur = byH[cur.parent]
            }
            return null
        }
        return all.filter { it.isFile }.mapNotNull { n -> path(n)?.let { n to it } }
    }
    fun trashHandle(all: List<MegaNode>) = all.firstOrNull { it.type == 4 }?.handle
}

object MegaImport {
    const val WORK = "mega_import"
    fun start(c: Context) {
        val req = OneTimeWorkRequestBuilder<MegaImportWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (Prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
        WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
    }
    fun stop(c: Context) { WorkManager.getInstance(c).cancelUniqueWork(WORK) }
    /** Continua sozinho depois (limite de transferência do MEGA ou limite de tempo do Android). */
    fun later(c: Context, minutes: Long) {
        val req = OneTimeWorkRequestBuilder<MegaImportWorker>().setInitialDelay(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(c).enqueueUniqueWork(WORK + "_later", ExistingWorkPolicy.REPLACE, req)
    }
}

class MegaImportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val keep = KeepAlive(ctx)
    private val started = android.os.SystemClock.elapsedRealtime()

    private fun notif(text: String, done: Int, total: Int, pct: Int): Notification =
        NotificationCompat.Builder(applicationContext, Backup.CHANNEL).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (total > 0) "Trazendo do MEGA • $done de $total" else "Trazendo do MEGA")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, pct.coerceIn(0, 100), total == 0).build()

    override suspend fun getForegroundInfo() = ForegroundInfo(Backup.NOTIF_ID + 1, notif("Preparando…", 0, 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun report(done: Int, total: Int, text: String, pct: Int) {
        setProgressAsync(workDataOf("done" to done, "total" to total, "name" to text, "pct" to pct))
        try { setForegroundAsync(ForegroundInfo(Backup.NOTIF_ID + 1, notif(text, done, total, pct), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) } catch (_: Exception) {}
    }

    override suspend fun doWork(): Result {
        if (!MegaStore.loggedIn) return Result.success()
        Backup.ensureChannel(applicationContext)
        try { setForeground(getForegroundInfo()) } catch (_: Exception) {}
        keep.acquire()
        Td.init(applicationContext)
        try {
            if (!Td.awaitReady()) { MegaImport.later(applicationContext, 2); return Result.success() }
            MegaStore.lastError = ""
            report(0, 0, "Lendo sua conta do MEGA…", 0)
            val mega = MegaStore.client()
            val all = withContext(Dispatchers.IO) { mega.fetchNodes() }
            MegaStore.nodes.value = all
            val done = MegaStore.done()
            val todo = MegaStore.files(all).filter { it.first.handle !in done && MegaStore.wanted(it.second) }.sortedBy { it.first.ts }
            val total = todo.size + done.size
            if (todo.isEmpty()) { report(total, total, "Tudo do MEGA já está na Nuvem.", 100); return Result.success(workDataOf("summary" to "Tudo do MEGA já está na Nuvem.")) }
            val dest = Repo.ensureDestination()
            val max = Meta.maxSize(Td.isPremium.value)
            val tmpDir = File(applicationContext.cacheDir, "mega").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            var n = done.size
            var sent = 0; var skippedBig = 0
            for ((node, path) in todo) {
                if (isStopped) break
                if (StallPolicy.nearTimeout(started, android.os.SystemClock.elapsedRealtime())) { MegaImport.later(applicationContext, 1); break }
                if (node.size > max) { skippedBig++; n++; continue }
                val album = "MEGA" + if (path.isBlank()) "" else "/$path"
                val f = File(tmpDir, node.handle + "_" + node.name.replace('/', '_'))
                try {
                    withContext(Dispatchers.IO) {
                        mega.download(node, f, { b ->
                            val pct = if (node.size > 0) (b * 50 / node.size).toInt() else 0
                            report(n, total, "Baixando ${node.name}", pct)
                        }, { isStopped })
                    }
                    val mime = FileScan.mimeOf(node.name)
                    val item = LocalItem(Uri.fromFile(f), f.absolutePath, node.name, f.length(), node.ts * 1000, node.ts * 1000, album, "MEGA/" + if (path.isBlank()) "" else "$path/", mime)
                    val hash = withContext(Dispatchers.IO) { Hash.quick(f) }
                    if (!Repo.alreadyOnCloud(hash, item.size, item.name)) {
                        val topic = Topics.idFor(dest.id, album)
                        Repo.uploadFile(f.absolutePath, item.size, Repo.captionFor(item, album), null, { p ->
                            report(n, total, "Enviando ${node.name}", 50 + (p * 50).toInt())
                        }, mime, applicationContext.cacheDir, meta = Repo.metaOf(item, hash), chatId = dest.id, topicId = topic)
                        sent++
                    }
                    MegaStore.markDone(node.handle, hash)
                    n++
                    report(n, total, "Pronto: ${node.name}", 100)
                } catch (e: MegaException) {
                    MegaStore.lastError = e.message ?: "Erro do MEGA"
                    DiagLog.log("MEGA", "importar ${node.name}: ${e.message}")
                    if (e.code == -17) { MegaImport.later(applicationContext, 60); break }
                    if (e.code == -15) break
                    n++
                } catch (e: java.util.concurrent.CancellationException) { throw e
                } catch (e: Exception) {
                    if (isStopped) break
                    MegaStore.lastError = "${node.name}: ${e.message ?: "falhou"}"
                    DiagLog.log("MEGA", "importar ${node.name} falhou", e)
                    n++
                } finally { f.delete() }
            }
            try { Repo.pushIndex(dest.id) } catch (_: Exception) {}
            DiagLog.log("MEGA", "rodada: $sent enviados, $skippedBig grandes demais, ${todo.size} na lista")
            val summary = "MEGA: $sent arquivos novos na Nuvem" + if (skippedBig > 0) " • $skippedBig acima do limite do Telegram ficaram de fora" else ""
            return Result.success(workDataOf("summary" to summary))
        } catch (e: java.util.concurrent.CancellationException) { throw e
        } catch (e: Exception) {
            MegaStore.lastError = e.message ?: "Erro ao importar do MEGA"
            DiagLog.log("MEGA", "importação parou", e)
            if (e is MegaException && e.code == -15) return Result.success()
            MegaImport.later(applicationContext, 5)
            return Result.success()
        } finally { keep.release() }
    }
}
