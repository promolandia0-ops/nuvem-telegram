package br.geracaoai.nuvem.play

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import br.geracaoai.nuvem.R
import br.geracaoai.nuvem.ui.MainActivity

class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playing = PlayerHolder.player?.isPlaying == true
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(PlayerHolder.title.ifBlank { "Nuvem" })
            .setContentText(if (playing) "Reproduzindo em segundo plano" else "Pausado")
            .setContentIntent(open)
            .setOngoing(playing)
            .setSilent(true)
            .build()
        startForeground(NOTIF, n)
        return START_STICKY
    }

    override fun onDestroy() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "player"
        const val NOTIF = 77
    }
}
