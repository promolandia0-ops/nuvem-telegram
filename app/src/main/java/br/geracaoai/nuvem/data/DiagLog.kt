package br.geracaoai.nuvem.data

import android.content.Context
import android.os.Build
import android.os.Environment
import br.geracaoai.nuvem.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Diário do app: o que o backup fez e os erros. Fica no celular; a tela de diagnóstico copia tudo. */
object DiagLog {
    private const val KEEP_LINES = 400
    private const val MAX_BYTES = 200_000L
    private var file: File? = null
    private val lock = Any()
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.forLanguageTag("pt-BR"))

    fun init(c: Context) {
        file = File(c.filesDir, "diag.log")
    }

    fun log(tag: String, msg: String) {
        val f = file ?: return
        val linha = "${fmt.format(Date())} [$tag] ${msg.replace('\n', ' ')}\n"
        synchronized(lock) {
            try {
                f.appendText(linha)
                if (f.length() > MAX_BYTES) {
                    f.writeText(f.readLines().takeLast(KEEP_LINES / 2).joinToString("\n", postfix = "\n"))
                }
            } catch (_: Exception) {
            }
        }
    }

    fun tail(n: Int = KEEP_LINES): String = synchronized(lock) {
        try {
            file?.takeIf { it.exists() }?.readLines()?.takeLast(n)?.joinToString("\n") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** Texto completo para copiar: versão, aparelho, estado do backup e o diário. */
    fun report(extra: String): String {
        val acesso = try { Environment.isExternalStorageManager() } catch (_: Throwable) { false }
        return buildString {
            append("NUVEM ${BuildConfig.VERSION_NAME}\n")
            append("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n")
            append("Acesso a todos os arquivos: $acesso\n")
            append(extra)
            append("\n--- diário ---\n")
            append(tail(300))
        }
    }
}
