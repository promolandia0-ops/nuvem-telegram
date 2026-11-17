package br.geracaoai.nuvem.play

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/** Player compartilhado pra PiP e áudio em segundo plano não matarem a reprodução. */
object PlayerHolder {
    @Volatile var player: ExoPlayer? = null
    @Volatile var title: String = ""
    @Volatile var backgroundAudio: Boolean = false
    @Volatile var shouldEnterPip: Boolean = true
    @Volatile var keepAlive: Boolean = false

    @OptIn(UnstableApi::class)
    fun obtain(ctx: Context): ExoPlayer {
        player?.let { return it }
        val renderers = DefaultRenderersFactory(ctx.applicationContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 30_000, 400, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val p = ExoPlayer.Builder(ctx.applicationContext, renderers).setLoadControl(load).build()
        player = p
        return p
    }

    fun releaseIfIdle() {
        if (keepAlive || backgroundAudio) return
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    fun startService(ctx: Context) {
        try { ctx.applicationContext.startForegroundService(Intent(ctx, PlaybackService::class.java)) } catch (_: Exception) {
            try { ctx.applicationContext.startService(Intent(ctx, PlaybackService::class.java)) } catch (_: Exception) {}
        }
    }

    fun stopService(ctx: Context) {
        try { ctx.applicationContext.stopService(Intent(ctx, PlaybackService::class.java)) } catch (_: Exception) {}
    }
}
