package br.geracaoai.nuvem.data

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.work.Backup
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Diário de erros e eventos do app. Aparece em Ajustes › Diagnóstico do app para copiar e mandar. */
object DiagLog {
    private const val MAX = 500
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private var writes = 0
    private val fmt = java.text.SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)

    @Synchronized
    fun init(c: Context) {
        file = File(c.filesDir, "diag.log")
        try { file?.readLines()?.takeLast(MAX)?.forEach { lines.addLast(it) } } catch (_: Exception) {}
    }

    @Synchronized
    fun log(tag: String, msg: String, t: Throwable? = null) {
        val body = buildString {
            append(msg)
            if (t != null) {
                append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message ?: "")
                t.stackTrace.take(6).forEach { append("\n    at ").append(it) }
            }
        }
        val line = "${fmt.format(Date())} [$tag] $body"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        try {
            val f = file ?: return
            writes++
            if (writes % 100 == 0) f.writeText(lines.joinToString("\n") + "\n")
            else f.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    @Synchronized
    fun clear() {
        lines.clear(); writes = 0
        try { file?.writeText("") } catch (_: Exception) {}
    }

    @Synchronized
    fun tail(n: Int = 300): String = lines.toList().takeLast(n).joinToString("\n")

    fun report(c: Context): String {
        val sb = StringBuilder()
        val ver = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (_: Exception) { "?" }
        sb.append("NUVEM – DIAGNÓSTICO\n")
        sb.append("Gerado: ").append(fmt.format(Date())).append('\n')
        sb.append("App: ").append(ver).append('\n')
        sb.append("Aparelho: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
            .append(", Android ").append(android.os.Build.VERSION.RELEASE).append('\n')
        sb.append("Chat do Telegram definido: ").append(Prefs.chatId != 0L).append('\n')
        sb.append("Conexão com o Telegram: ").append(Td.connection.value).append(" | Pronto: ").append(Td.ready).append('\n')
        try {
            sb.append("MEGA: conectado=").append(br.geracaoai.nuvem.mega.MegaStore.loggedIn)
                .append(" | já enviados=").append(br.geracaoai.nuvem.mega.MegaStore.done().size)
                .append(" | pastas=").append(br.geracaoai.nuvem.mega.MegaStore.folders.ifEmpty { setOf("todas") }.joinToString(","))
                .append(" | último erro=").append(br.geracaoai.nuvem.mega.MegaStore.lastError.ifBlank { "nenhum" }).append('\n')
        } catch (_: Exception) {}
        sb.append("Backup de tudo: ").append(Prefs.backupAll).append(" | Automático: ").append(Prefs.autoBackup).append('\n')
        sb.append("Pausado: ").append(Prefs2.backupPaused).append(" | Trava de backup ocupada: ").append(Backup.runningInProcess()).append('\n')
        sb.append("Telegram pronto: ").append(Td.ready).append(" | Estado: ").append(Td.auth.value?.javaClass?.simpleName ?: "nenhum").append('\n')
        sb.append("Acesso a todos os arquivos: ").append(AllFilesAccess.granted()).append('\n')
        sb.append("Último erro salvo: ").append(Prefs.lastError.ifBlank { "nenhum" }).append('\n')
        sb.append("Trabalhos (NOW/PERIODIC/CONTINUE): ").append(workStates(c)).append('\n')
        sb.append("\n--- Últimas ações ---\n")
        ActionLog.recent(15).forEach { e ->
            sb.append(fmt.format(Date(e.at))).append(' ').append(e.action).append(": ").append(e.detail).append('\n')
        }
        sb.append("\n--- Eventos e erros ---\n")
        sb.append(tail(300)).append('\n')
        return sb.toString()
    }

    private fun workStates(c: Context): String = try {
        val wm = WorkManager.getInstance(c)
        listOf(Backup.NOW, Backup.PERIODIC, Backup.CONTINUE).joinToString(" | ") { n ->
            val infos: List<WorkInfo> = wm.getWorkInfosForUniqueWork(n).get(2, TimeUnit.SECONDS)
            infos.lastOrNull()?.state?.name ?: "nenhum"
        }
    } catch (e: Exception) { "erro ao ler: ${e.javaClass.simpleName}" }
}
