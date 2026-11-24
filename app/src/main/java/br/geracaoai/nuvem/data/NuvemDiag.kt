package br.geracaoai.nuvem.data

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnóstico de travamento: se a tela principal ficar mais de 4 s sem responder, grava onde estava parada.
 * Também grava erros que derrubam o app. Arquivo: Download/nuvem-diagnostico.txt (ou Android/data/.../files).
 */
object NuvemDiag {
    private val last = AtomicLong(System.currentTimeMillis())

    fun start(app: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            write(app, "ERRO FATAL em ${t.name}:\n" + e.stackTraceToString())
            prev?.uncaughtException(t, e)
        }
        val main = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread
        Thread({
            var dumped = false
            while (true) {
                val sent = System.currentTimeMillis()
                main.post { last.set(System.currentTimeMillis()) }
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                val silent = System.currentTimeMillis() - last.get()
                if (silent > 4000 && !dumped) {
                    val st = mainThread.stackTrace.joinToString("\n") { "  at $it" }
                    write(app, "TRAVOU a tela principal por ${silent} ms. Onde estava parada:\n$st")
                    dumped = true
                }
                if (last.get() >= sent) dumped = false
            }
        }, "nuvem-diag").apply { isDaemon = true }.start()
    }

    private fun write(app: Context, text: String) {
        val line = "\n==== ${java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.US).format(java.util.Date())} ====\n$text\n"
        val targets = listOf(
            { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "nuvem-diagnostico.txt") },
            { File(app.getExternalFilesDir(null), "nuvem-diagnostico.txt") },
        )
        for (t in targets) {
            try { t().appendText(line); return } catch (_: Exception) {}
        }
    }
}
