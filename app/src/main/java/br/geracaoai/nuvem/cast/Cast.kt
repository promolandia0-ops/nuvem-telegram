package br.geracaoai.nuvem.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import br.geracaoai.nuvem.R
import br.geracaoai.nuvem.data.CloudItem
import br.geracaoai.nuvem.data.DiagLog
import br.geracaoai.nuvem.data.Repo
import br.geracaoai.nuvem.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Transmitir pra TV (DLNA: Samsung, LG, Philips, TCL, Sony, Fire TV com app, receptores de mídia…). */
object Cast {
    data class Estado(
        val tv: TvDevice, val titulo: String, val msgId: Long,
        val tocando: Boolean = false, val pos: Long = 0, val dur: Long = 0,
        val carregando: Boolean = true, val erro: String? = null, val video: Boolean = true,
    )

    val estado = MutableStateFlow<Estado?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var poll: Job? = null
    private var ctrl: RendererController? = null
    private var volume = 20
    private var descoberta: RendererDiscovery? = null

    fun descoberta(c: Context): RendererDiscovery =
        descoberta ?: RendererDiscovery(c.applicationContext).also { descoberta = it }

    /** Manda o item da nuvem pra TV. Devolve a mensagem de erro (null = foi). */
    suspend fun transmitir(c: Context, tv: TvDevice, ci: CloudItem): String? = withContext(Dispatchers.IO) {
        estado.value = Estado(tv, ci.name, ci.msgId, video = ci.isVideo)
        try {
            val fonte = if (ci.isVideo) {
                val p = Repo.playable(ci)
                CastServer.Fonte(p.fileId, p.size, mimeDe(p.mime, ci.name, true), ci.name, if (p.complete) p.path else "")
            } else {
                val path = Repo.viewPath(ci) ?: return@withContext falhou("Não deu pra baixar a foto.")
                CastServer.Fonte(0, java.io.File(path).length(), mimeDe(ci.mime, ci.name, false), ci.name, path)
            }
            servindo.value = null
            val url = CastServer.servir(fonte) ?: return@withContext falhou("O celular não está no Wi-Fi. Conecte no mesmo Wi-Fi da TV.")
            val r = RendererController(tv)
            r.play(url, ci.name, fonte.mime, ci.isVideo)
            ctrl = r
            c.startForegroundService(Intent(c, CastService::class.java))
            acompanhar()
            DiagLog.log("TV", "transmitindo ${ci.name} em ${tv.name}")
            null
        } catch (e: Exception) {
            DiagLog.log("TV", "não transmitiu", e)
            falhou("A TV não aceitou: ${e.message ?: "erro"}. Confira se ela está ligada e no mesmo Wi-Fi.")
        }
    }

    /** Vídeo sendo entregue pra outro app (Web Video Caster, MX Player, Controle Samsung…). */
    val servindo = MutableStateFlow<String?>(null)

    /**
     * "Transmitir com…": abre a lista do Android com os apps de vídeo/transmissão. O Nuvem entrega o
     * vídeo direto do Telegram por um endereço do Wi-Fi de casa, que esses apps (e a TV) conseguem abrir.
     */
    suspend fun abrirCom(c: Context, ci: CloudItem): String? = withContext(Dispatchers.IO) {
        try {
            val fonte = if (ci.isVideo) {
                val p = Repo.playable(ci)
                CastServer.Fonte(p.fileId, p.size, mimeDe(p.mime, ci.name, true), ci.name, if (p.complete) p.path else "")
            } else {
                val path = Repo.viewPath(ci) ?: return@withContext "Não deu pra baixar a foto."
                CastServer.Fonte(0, java.io.File(path).length(), mimeDe(ci.mime, ci.name, false), ci.name, path)
            }
            val url = CastServer.servir(fonte, soNoCelular = true) ?: return@withContext "Não deu pra preparar o vídeo."
            servindo.value = ci.name
            c.startForegroundService(Intent(c, CastService::class.java))
            val ver = Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(url), fonte.mime)
                .putExtra("title", ci.name).putExtra(Intent.EXTRA_TITLE, ci.name)
            withContext(Dispatchers.Main) {
                c.startActivity(Intent.createChooser(ver, "Transmitir com…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            DiagLog.log("TV", "abrir com outro app: ${ci.name}")
            null
        } catch (e: Exception) {
            DiagLog.log("TV", "abrir com outro app falhou", e)
            "Não deu pra abrir: ${e.message ?: "erro"}"
        }
    }

    private fun falhou(msg: String): String { estado.value = estado.value?.copy(erro = msg, carregando = false); return msg }

    private fun acompanhar() {
        poll?.cancel()
        poll = scope.launch {
            var falhas = 0
            while (isActive) {
                val r = ctrl ?: break
                try {
                    val t = r.position()
                    val st = r.state()
                    falhas = 0
                    estado.value = estado.value?.copy(
                        tocando = st == "PLAYING", carregando = st == "TRANSITIONING" || (st != "PLAYING" && st != "PAUSED_PLAYBACK" && t == null),
                        pos = t?.position ?: estado.value?.pos ?: 0, dur = t?.duration ?: estado.value?.dur ?: 0, erro = null)
                } catch (_: Exception) { if (++falhas >= 8) estado.value = estado.value?.copy(erro = "Sem resposta da TV.") }
                delay(1000)
            }
        }
    }

    fun alternar() = scope.launch {
        val e = estado.value ?: return@launch
        runCatching { if (e.tocando) ctrl?.pause() else ctrl?.playAgain() }
        estado.value = e.copy(tocando = !e.tocando)
    }

    fun irPara(seg: Long) = scope.launch {
        val r = ctrl ?: return@launch
        estado.value = estado.value?.copy(pos = seg)
        runCatching { r.seek(r.clock(seg)) }
    }

    fun pular(delta: Long) { val e = estado.value ?: return; irPara((e.pos + delta).coerceIn(0, if (e.dur > 0) e.dur else Long.MAX_VALUE)) }

    fun volume(delta: Int) = scope.launch {
        val r = ctrl ?: return@launch
        volume = (r.getVolume() ?: volume) + delta
        volume = volume.coerceIn(0, 100)
        runCatching { r.setVolume(volume) }
    }

    fun parar(c: Context) {
        val r = ctrl
        ctrl = null; poll?.cancel(); estado.value = null; servindo.value = null
        scope.launch { runCatching { r?.stop() }; CastServer.parar() }
        c.stopService(Intent(c, CastService::class.java))
    }

    private fun mimeDe(m: String, nome: String, video: Boolean): String {
        if (m.isNotBlank() && m.contains('/') && m != "application/octet-stream") return m
        return when (nome.substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"; "ts" -> "video/mp2t"; "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"
            "jpg", "jpeg" -> "image/jpeg"; else -> if (video) "video/mp4" else "image/jpeg"
        }
    }
}

/** Mantém a transmissão viva com a tela desligada (o celular é quem entrega o vídeo pra TV). */
class CastService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACAO_PARAR -> { Cast.parar(this); return START_NOT_STICKY }
            ACAO_ALTERNAR -> Cast.alternar()
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Transmitir pra TV", NotificationManager.IMPORTANCE_LOW))
        val e = Cast.estado.value
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val parar = PendingIntent.getService(this, 1, Intent(this, CastService::class.java).setAction(ACAO_PARAR), PendingIntent.FLAG_IMMUTABLE)
        val alternar = PendingIntent.getService(this, 2, Intent(this, CastService::class.java).setAction(ACAO_ALTERNAR), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (e != null) "Na TV: ${e.tv.name}" else "Nuvem entregando vídeo pra outro app")
            .setContentText(e?.titulo ?: Cast.servindo.value ?: "Transmitindo")
            .setContentIntent(abrir).setOngoing(true).setSilent(true)
            .apply { if (e != null) addAction(0, "Pausar/Tocar", alternar) }
            .addAction(0, "Parar", parar)
            .build()
        startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nuvem:tv").apply { acquire(6 * 3600_000L) }
        @Suppress("DEPRECATION")
        if (wifi == null) wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nuvem:tv").apply { acquire() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }; wake = null
        runCatching { wifi?.takeIf { it.isHeld }?.release() }; wifi = null
        super.onDestroy()
    }

    companion object {
        const val CANAL = "tv"
        const val NOTIF = 78
        const val ACAO_PARAR = "parar"
        const val ACAO_ALTERNAR = "alternar"
    }
}
