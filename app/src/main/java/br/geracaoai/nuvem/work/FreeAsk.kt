package br.geracaoai.nuvem.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import br.geracaoai.nuvem.R
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.data.Repo
import br.geracaoai.nuvem.ui.MainActivity
import br.geracaoai.nuvem.ui.fmtBytes

/**
 * Depois do backup: se já há arquivos conferidos no Telegram, avisa "Liberar X?" com OK ou Cancelar.
 * Nada é apagado aqui: o OK abre o app, que confere de novo no Telegram e manda para a lixeira do celular.
 */
object FreeAsk {
    const val CHANNEL = "liberar"
    private const val NOTIF_ID = 4120
    /** A tela principal lê isto e mostra a janela OK / Cancelar. */
    val request = mutableStateOf(false)

    fun check(c: Context) {
        if (!Prefs.autoFreeAsk) return
        val f = try { Repo.freeableQuick(c) } catch (_: Exception) { return }
        if (f.items.isEmpty() || f.bytes < 1_000_000L) return
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Liberar espaço", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                c, 1,
                Intent(c, MainActivity::class.java).putExtra("freeAsk", true).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Liberar ${fmtBytes(f.bytes)}?")
                .setContentText("${f.items.size} arquivos já estão no Telegram. Toque para confirmar.")
                .setContentIntent(open).setAutoCancel(true).build()
            nm.notify(NOTIF_ID, n)
        } catch (_: Exception) {}
    }
}
