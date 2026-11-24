package br.geracaoai.nuvem.work

import android.content.Context
import br.geracaoai.nuvem.data.DiagLog
import br.geracaoai.nuvem.data.Hash
import br.geracaoai.nuvem.data.HashCache
import br.geracaoai.nuvem.data.Library
import br.geracaoai.nuvem.data.LocalFile
import br.geracaoai.nuvem.data.Meta
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.tg.CloudRec
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.tg.TdException
import br.geracaoai.nuvem.tg.Tg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/** Estado do backup, mostrado na tela e na notificação. */
data class BackupState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val message: String = "",
)

/**
 * Motor do backup. Fluxo: conecta, lê o aparelho, lê a nuvem pelas legendas,
 * calcula o hash de cada arquivo e sobe só o que ainda não está na nuvem.
 * Por ser conferido pelo hash, pode parar e recomeçar sem reenviar nada.
 */
object Engine {
    private val lock = Mutex()
    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state.asStateFlow()
    @Volatile private var stopReq = false
    @Volatile private var appCtx: Context? = null

    private fun set(s: BackupState) {
        _state.value = s
        val c = appCtx
        if (c != null && s.running) Sched.postProgress(c, s)
    }

    private fun update(f: (BackupState) -> BackupState) = set(f(_state.value))

    fun markQueued() {
        _state.value = _state.value.copy(message = "Iniciando o backup…")
    }

    fun pause() {
        Prefs.backupPaused = true
        update { it.copy(paused = true, message = "Pausado. Toque em Retomar para continuar.") }
    }

    fun resume() {
        Prefs.backupPaused = false
        update { it.copy(paused = false, message = "Continuando…") }
    }

    fun requestStop() {
        stopReq = true
        Prefs.backupPaused = false
        update { it.copy(paused = false, message = "Parando…") }
    }

    private fun fail(msg: String) {
        DiagLog.log("ERRO", msg)
        Prefs.lastError = msg
        update { it.copy(message = msg, phase = "") }
    }

    /** Roda um backup completo. Se já houver um rodando, não faz nada. */
    suspend fun run(ctx: Context) {
        if (!lock.tryLock()) {
            DiagLog.log("BACKUP", "pedido ignorado: já existe um backup rodando")
            return
        }
        appCtx = ctx.applicationContext
        stopReq = false
        HashCache.load(ctx)
        set(BackupState(running = true, phase = "Preparando", message = "Conectando ao Telegram…"))
        DiagLog.log("BACKUP", "início")
        try {
            work(ctx)
        } catch (e: CancellationException) {
            DiagLog.log("BACKUP", "interrompido")
            throw e
        } catch (e: Exception) {
            fail("Falhou: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            HashCache.save()
            set(_state.value.copy(running = false, paused = false, phase = "", current = ""))
            Sched.clearProgress(ctx)
            lock.unlock()
        }
    }

    private suspend fun work(ctx: Context) {
        if (!Td.ready && !Td.awaitReady(90_000)) {
            fail("Não consegui conectar ao Telegram. Verifique a internet e abra o app de novo.")
            return
        }
        if (Prefs.backupPaused) {
            update { it.copy(message = "Backup pausado.") }
            return
        }
        val chat = Tg.ensureDestination()
        val chats = (Prefs.destChats() + chat).distinct()

        // 1) Tudo que está no aparelho, sem ícones e miniaturas.
        update { it.copy(phase = "Lendo o aparelho", message = "Procurando fotos e vídeos em todo o aparelho…") }
        val all = withContext(Dispatchers.IO) { Library.scan(ctx) }
        if (all.isEmpty()) {
            fail("Não encontrei fotos ou vídeos. Confira se o app tem permissão de fotos e vídeos nas configurações do celular.")
            return
        }
        val files = withContext(Dispatchers.IO) {
            all.filterNot { Library.isIcon(it.path, it.name, it.size, it.mime) }
        }
        DiagLog.log("BACKUP", "aparelho: ${all.size} arquivos, ${all.size - files.size} ícones ou miniaturas deixados de fora")

        // 2) O que já está na nuvem, pelas legendas de todos os destinos.
        update { it.copy(phase = "Lendo a nuvem", message = "Conferindo o que já está no Telegram…") }
        var cloud: List<CloudRec> = withContext(Dispatchers.IO) { Tg.scanCloud(chats) }
        if (!Prefs.iconsPurged) cloud = purgeIcons(cloud)
        val cloudHashes = HashSet(cloud.map { it.meta.hash })
        DiagLog.log("BACKUP", "nuvem: ${cloud.size} mensagens, ${cloudHashes.size} arquivos diferentes")
        if (stopReq) {
            update { it.copy(message = "Parado.") }
            return
        }

        // 3) Confere cada arquivo pelo hash (com cache) e separa o que falta subir.
        val limit = Meta.maxSize(Td.isPremium.value)
        val seen = HashSet<String>(cloudHashes)
        val pending = ArrayList<Pair<LocalFile, String>>()
        var tooBig = 0
        update { it.copy(phase = "Conferindo arquivos", total = files.size, done = 0, message = "Conferindo ${files.size} arquivos…") }
        withContext(Dispatchers.IO) {
            for ((i, f) in files.withIndex()) {
                if (stopReq) break
                if (f.size > limit) {
                    tooBig++
                    continue
                }
                val file = File(f.path)
                val mtime = file.lastModified()
                val cached = HashCache.get(f.path, f.size, mtime)
                val computed: String? = if (cached != null) cached else try {
                    Hash.quick(file)
                } catch (e: Exception) {
                    DiagLog.log("HASH", "${f.name}: ${e.javaClass.simpleName}")
                    null
                }
                if (computed == null) continue
                if (cached == null) HashCache.put(f.path, f.size, mtime, computed)
                if (seen.add(computed)) pending += (f to computed)
                if (i % 100 == 0) update { it.copy(done = i) }
            }
        }
        HashCache.save()

        val todo = pending.sortedByDescending { it.first.taken }
        Prefs.lastLocal = files.size
        Prefs.lastCloud = cloudHashes.size
        Prefs.lastPending = todo.size
        DiagLog.log(
            "BACKUP",
            "conferido: ${files.size} no aparelho, ${cloudHashes.size} já na nuvem, ${todo.size} para subir, $tooBig grandes demais",
        )
        if (stopReq) {
            update { it.copy(message = "Parado.") }
            return
        }
        if (todo.isEmpty()) {
            Prefs.lastBackup = System.currentTimeMillis()
            Prefs.lastError = ""
            update { it.copy(phase = "", done = 0, total = 0, message = "Tudo em dia. Nada novo para subir.") }
            return
        }

        // 4) Sobe um por um, do mais recente para o mais antigo.
        val total = todo.size
        update { it.copy(phase = "Enviando", total = total, done = 0, current = "", message = "Enviando $total arquivos…") }
        var sent = 0
        var skipped = 0
        var failsInRow = 0
        for ((f, hash) in todo) {
            while (Prefs.backupPaused && !stopReq) {
                update { it.copy(paused = true) }
                delay(800)
            }
            if (stopReq) break
            update { it.copy(paused = false, current = f.name) }
            val file = File(f.path)
            if (!file.exists()) {
                skipped++
                continue
            }
            try {
                val meta = Meta(hash, f.size, f.taken, f.name, f.mime, f.bucket, f.path, original = true)
                val ok = sendWithRetry(chat, file, meta.toCaption())
                if (!ok) break
                sent++
                failsInRow = 0
                cloudHashes.add(hash)
                DiagLog.log("ENVIADO", f.name)
                update { it.copy(done = sent) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TdException) {
                if (e.code == 429) throw e
                failsInRow++
                DiagLog.log("FALHOU", "${f.name}: ${e.message}")
                if (failsInRow >= 5) throw TdException(e.code, "5 arquivos seguidos falharam. Último erro: ${e.message}")
            } catch (e: Exception) {
                failsInRow++
                DiagLog.log("FALHOU", "${f.name}: ${e.javaClass.simpleName} ${e.message}")
                if (failsInRow >= 5) throw TdException(0, "5 arquivos seguidos falharam.")
            }
        }

        Prefs.lastCloud = cloudHashes.size
        Prefs.lastPending = total - sent - skipped
        Prefs.lastBackup = System.currentTimeMillis()
        Prefs.lastError = ""
        val grandes = if (tooBig > 0) " $tooBig grandes demais para o Telegram ficaram de fora." else ""
        val titulo = if (stopReq) "Parado" else "Backup concluído"
        update {
            it.copy(phase = "", current = "", paused = false, done = sent, total = total, message = "$titulo: $sent enviados.$grandes")
        }
        DiagLog.log("BACKUP", "fim: $sent enviados de $total; parado=$stopReq")
    }

    /** Ícones que já foram para a nuvem antes: apaga uma vez e marca como feito. */
    private suspend fun purgeIcons(cloud: List<CloudRec>): List<CloudRec> {
        val icons = withContext(Dispatchers.IO) {
            cloud.filter { r ->
                val m = r.meta
                m.path.isNotBlank() && File(m.path).exists() && Library.isIcon(m.path, m.name, m.size, m.mime)
            }
        }
        if (icons.isNotEmpty()) {
            update { it.copy(phase = "Limpando ícones", message = "Apagando ${icons.size} ícones que estavam na nuvem…") }
            withContext(Dispatchers.IO) {
                for ((chat, itens) in icons.groupBy { it.chatId }) {
                    Tg.deleteMessages(chat, itens.map { it.msgId })
                }
            }
            DiagLog.log("ÍCONES", "apagados da nuvem: ${icons.size}")
        }
        Prefs.iconsPurged = true
        return cloud - icons.toSet()
    }

    /**
     * Tenta enviar. Espera quando o Telegram pede pausa (FLOOD_WAIT) e tenta de novo em erros passageiros.
     * Retorna false se o usuário parou no meio.
     */
    private suspend fun sendWithRetry(chat: Long, file: File, legenda: String): Boolean {
        var tentativas = 0
        while (true) {
            if (stopReq) return false
            try {
                Tg.upload(chat, file, legenda)
                return true
            } catch (e: TdException) {
                val espera = esperaDoTelegram(e.message)
                if (espera != null) {
                    if (espera > 900) {
                        throw TdException(429, "O Telegram pediu uma pausa de ${espera / 60} min. Tente de novo mais tarde.")
                    }
                    DiagLog.log("ESPERA", "Telegram pediu pausa de ${espera}s")
                    update { it.copy(message = "O Telegram pediu uma pausa de ${espera}s. Continuo sozinho.") }
                    esperar(espera)
                    continue
                }
                tentativas++
                if (tentativas >= 3) throw e
                delay(5_000)
            }
        }
    }

    private suspend fun esperar(segundos: Long) {
        var resta = segundos
        while (resta > 0 && !stopReq) {
            delay(1_000)
            resta--
        }
    }

    private fun esperaDoTelegram(msg: String?): Long? {
        if (msg == null) return null
        val m = Regex("retry after (\\d+)|FLOOD_WAIT_(\\d+)").find(msg) ?: return null
        return m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toLongOrNull()
    }
}
