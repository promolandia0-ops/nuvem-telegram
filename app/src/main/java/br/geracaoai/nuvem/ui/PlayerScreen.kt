package br.geracaoai.nuvem.ui

import br.geracaoai.nuvem.work.Restore

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.provider.MediaStore
import android.view.TextureView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.play.PlayerHolder
import br.geracaoai.nuvem.tg.TdDataSource
import br.geracaoai.nuvem.tg.TgStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun orientacaoCodigo(modo: ModoOrientacao, paisagem: Boolean?): Int = when (modo) {
    ModoOrientacao.PAISAGEM -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    ModoOrientacao.RETRATO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    ModoOrientacao.AUTO -> when (paisagem) {
        true -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        false -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        null -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    items: List<CloudItem>,
    index: Int,
    onIndex: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val ci = items.getOrNull(index) ?: return
    val ctx = LocalContext.current
    val act = ctx.findActivity() as? MainActivity
    val scope = rememberCoroutineScope()
    val hap = LocalHapticFeedback.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    var playable by remember(ci.msgId) { mutableStateOf<Playable?>(null) }
    var resolveError by remember(ci.msgId) { mutableStateOf<String?>(null) }
    var retry by remember(ci.msgId) { mutableIntStateOf(0) }
    var resolving by remember(ci.msgId) { mutableStateOf(true) }
    val progressMap by TgStream.flow().collectAsState()

    LaunchedEffect(ci.msgId, retry) {
        resolving = true; playable = null; resolveError = null
        try { playable = Repo.playable(ci) }
        catch (e: Exception) { resolveError = e.message ?: "Não deu pra abrir o vídeo." }
        resolving = false
    }
    LaunchedEffect(index, items.size) {
        for (i in (index - 2)..(index + 2)) {
            val it = items.getOrNull(i) ?: continue
            launch { try { Repo.thumbPath(it) } catch (_: Exception) {} }
        }
    }

    val src = playable
    val prog = src?.let { progressMap[it.fileId] }
    val videos = remember(items) { BooleanArray(items.size) { items[it].isVideo } }
    val prevIdx = PlaybackNav.anteriorVideo(videos, index)
    val nextIdx = PlaybackNav.proximoVideo(videos, index)

    var chrome by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    var hud by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var aspecto by remember { mutableStateOf(ModoAspecto.AJUSTAR) }
    var orient by remember { mutableStateOf(ModoOrientacao.AUTO) }
    var loop by remember { mutableStateOf(ModoRepeticao.DESLIGADO) }
    var pontoA by remember { mutableStateOf<Long?>(null) }
    var pontoB by remember { mutableStateOf<Long?>(null) }
    var sonoMin by remember { mutableIntStateOf(0) }
    var sonoInicio by remember { mutableLongStateOf(0L) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var vsize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    var bgAudio by remember { mutableStateOf(PlayerHolder.backgroundAudio) }
    var brightness by remember { mutableFloatStateOf(act?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }

    val player = remember(src?.fileId, retry) {
        val s = src ?: return@remember null
        val p = PlayerHolder.obtain(ctx)
        PlayerHolder.title = ci.name
        p.stop(); p.clearMediaItems()
        if (s.complete && s.path.isNotBlank() && File(s.path).exists()) {
            p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(s.path))))
        } else {
            p.setMediaSource(
                ProgressiveMediaSource.Factory(TdDataSource.Factory(s.fileId, s.size))
                    .createMediaSource(MediaItem.fromUri("tdlib://file/${s.fileId}"))
            )
        }
        p.playWhenReady = true
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.prepare()
        p
    }

    DisposableEffect(player) {
        val p = player
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ready = state == Player.STATE_READY || state == Player.STATE_ENDED
                if (state == Player.STATE_READY && p != null) {
                    val d = p.duration.coerceAtLeast(0)
                    dur = d
                    val saved = ResumePrefs.obter(ctx, ResumePrefs.chave(ci), d)
                    if (saved != null && ResumePrefs.deveRetomar(saved, d) && p.currentPosition < 2_000) {
                        p.seekTo(saved)
                        hud = "Retomado em ${TempoFmt.fmt(saved)}"
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                playError = error.cause?.message ?: error.message ?: "Não deu pra reproduzir este vídeo."
                ready = false
            }
            override fun onVideoSizeChanged(s: VideoSize) { vsize = s }
        }
        p?.addListener(listener)
        onDispose {
            if (p != null) {
                val d = p.duration; val posNow = p.currentPosition
                ResumePrefs.salvar(ctx, ResumePrefs.chave(ci), posNow, d)
                ResumePrefs.marcarAlterado()
                p.removeListener(listener)
            }
            if (!PlayerHolder.backgroundAudio && !PlayerHolder.keepAlive) {
                try { p?.stop() } catch (_: Exception) {}
            }
        }
    }

    DisposableEffect(Unit) {
        PlayerHolder.shouldEnterPip = true
        onDispose {
            PlayerHolder.shouldEnterPip = false
            locked = false
            act?.aplicarOrientacao(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            if (!PlayerHolder.backgroundAudio) {
                PlayerHolder.keepAlive = false
                PlayerHolder.releaseIfIdle()
                PlayerHolder.stopService(ctx)
            }
        }
    }

    LaunchedEffect(orient, vsize.width, vsize.height, vsize.unappliedRotationDegrees) {
        val pais = Orientacao.ehPaisagem(vsize.width, vsize.height, vsize.unappliedRotationDegrees)
        act?.aplicarOrientacao(orientacaoCodigo(orient, pais))
    }
    LaunchedEffect(player, loop, pontoA, pontoB) {
        val p = player ?: return@LaunchedEffect
        p.repeatMode = if (loop == ModoRepeticao.UM) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (loop != ModoRepeticao.AB) return@LaunchedEffect
        while (true) {
            Repeticao.posicaoAposTick(p.currentPosition, pontoA, pontoB)?.let { p.seekTo(it) }
            delay(200)
        }
    }
    LaunchedEffect(sonoMin, sonoInicio, player) {
        if (sonoMin <= 0) return@LaunchedEffect
        val p = player ?: return@LaunchedEffect
        while (true) {
            if (TemporizadorSono.esgotou(sonoInicio, sonoMin, System.currentTimeMillis())) {
                p.pause(); hud = "Temporizador: fim"; sonoMin = 0; break
            }
            delay(1000)
        }
    }
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            pos = p.currentPosition
            dur = p.duration.coerceAtLeast(0)
            buffered = p.bufferedPosition.coerceAtLeast(0)
            delay(200)
        }
    }
    LaunchedEffect(chrome, playing, locked) {
        if (chrome && playing && !locked) { delay(3500); chrome = false }
    }
    LaunchedEffect(hud) { if (hud != null) { delay(1200); hud = null } }

    fun seekRel(esquerda: Boolean) {
        val p = player ?: return
        val alvo = PuloToque.alvo(p.currentPosition, p.duration.coerceAtLeast(0), esquerda)
        p.seekTo(alvo); hud = PuloToque.hud(esquerda)
    }
    fun aplicaVolume(delta: Float) {
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val n = (cur + delta * maxVol).toInt().coerceIn(0, maxVol)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, n, 0)
        hud = "Volume ${(n * 100 / maxVol)}%"
    }
    fun aplicaBrilho(delta: Float) {
        brightness = (brightness + delta).coerceIn(0.01f, 1f)
        act?.brilho(brightness)
        hud = "Brilho ${(brightness * 100).toInt()}%"
    }
    fun captura() {
        val bmp = texture?.bitmap
        if (bmp == null) { hud = "Não deu pra capturar o quadro"; return }
        val ok = runCatching {
            val nome = "Nuvem_${ci.name.substringBeforeLast('.').take(40)}_${System.currentTimeMillis()}.jpg"
            val vals = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, nome)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Nuvem")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vals) ?: return@runCatching false
            ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: return@runCatching false
            true
        }.getOrDefault(false)
        hud = if (ok) "Captura salva em Imagens › Nuvem" else "Falha ao salvar a captura"
    }
    fun compartilhar() {
        val path = src?.path?.takeIf { it.isNotBlank() && File(it).exists() }
        if (path == null) { hud = "Espere o vídeo carregar um pouco para compartilhar"; return }
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(path))
            val i = Intent(Intent.ACTION_SEND).apply {
                type = src?.mime?.ifBlank { "video/*" } ?: "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(i, "Compartilhar vídeo"))
        } catch (e: Exception) { hud = e.message ?: "Não deu pra compartilhar" }
    }
    fun faixas(): List<Pair<String, () -> Unit>> {
        val p = player ?: return emptyList()
        val out = ArrayList<Pair<String, () -> Unit>>()
        p.currentTracks.groups.forEach { g ->
            if (g.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (j in 0 until g.length) {
                val f = g.getTrackFormat(j)
                val rotulo = InfoVideo.rotuloFaixa(out.size, f.language, f.channelCount, f.sampleMimeType)
                val group = g; val idx = j
                out += rotulo to {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(idx))).build()
                    hud = "Áudio: $rotulo"
                }
            }
        }
        return out
    }

    BackHandler {
        if (locked) { locked = false; chrome = true }
        else onClose()
    }

    val showLoad = (resolving || src == null || (!ready && playError == null)) && resolveError == null
    val loadText = when {
        resolveError != null -> null
        playError != null -> null
        resolving || src == null -> "Abrindo o vídeo…"
        else -> prog?.rotulo ?: "Carregando o vídeo…"
    }
    val loadPct = prog?.percent ?: 0

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (player != null) {
            AndroidView({ c ->
                PlayerView(c).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                    resizeMode = when (aspecto) {
                        ModoAspecto.PREENCHER -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        ModoAspecto.ESTICAR -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    // TextureView pra captura de quadro
                    val tv = (this.videoSurfaceView as? TextureView)
                    if (tv != null) texture = tv
                }
            }, Modifier.fillMaxSize().graphicsLayer(
                scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y
            ), update = {
                it.player = player
                it.resizeMode = when (aspecto) {
                    ModoAspecto.PREENCHER -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    ModoAspecto.ESTICAR -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                (it.videoSurfaceView as? TextureView)?.let { t -> texture = t }
            })
        }

        Box(Modifier.fillMaxSize().pointerInput(locked, player) {
            detectTapGestures(
                onTap = {
                    if (locked) { chrome = !chrome }
                    else chrome = !chrome
                },
                onDoubleTap = { offset ->
                    if (locked) return@detectTapGestures
                    val w = size.width
                    if (w <= 0) return@detectTapGestures
                    hap.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    seekRel(esquerda = offset.x < w / 2f)
                }
            )
        }.pointerInput(locked) {
            if (locked) return@pointerInput
            detectTransformGestures { _, drag, z, _ ->
                if (z != 1f) {
                    zoom = (zoom * z).coerceIn(1f, 6f)
                    pan = if (zoom == 1f) Offset.Zero else pan + drag
                }
            }
        }.pointerInput(locked) {
            if (locked) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(false)
                val start = down.position
                var last = start
                var used = false
                do {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull() ?: break
                    val dy = (last.y - c.position.y) / size.height.toFloat()
                    if (kotlin.math.abs(c.position.y - start.y) > 24 && ev.changes.size == 1) {
                        used = true
                        if (start.x < size.width / 2f) aplicaBrilho(dy) else aplicaVolume(dy)
                        last = c.position
                        c.consume()
                    }
                } while (ev.changes.any { it.pressed })
                used
            }
        })

        // Carregando / erro — no centro, nunca escondido atrás dos botões
        if (showLoad || resolveError != null || playError != null) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (resolveError != null || playError != null) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(resolveError ?: playError ?: "", color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Button({ playError = null; retry++ }) { Text("Tentar de novo") }
                } else {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp),
                        progress = { if (loadPct > 0) loadPct / 100f else 0f },
                        trackColor = Color.White.copy(alpha = 0.2f))
                    Spacer(Modifier.height(16.dp))
                    Text(loadText ?: "Carregando…", color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    if (loadPct > 0) Text("$loadPct%", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else if (buffering && ready) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White, strokeWidth = 3.dp)
        }

        hud?.let {
            Surface(Modifier.align(Alignment.Center).padding(bottom = 96.dp), color = Color.Black.copy(alpha = 0.7f), shape = CircleShape) {
                Text(it, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold)
            }
        }

        AnimatedVisibility(locked && chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()) {
            TextButton({ locked = false; chrome = true }) { Icon(Icons.Rounded.LockOpen, null, tint = Color.White); Spacer(Modifier.width(8.dp)); Text("Desbloquear", color = Color.White) }
        }

        AnimatedVisibility(chrome && !locked, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.0f))) {
                // Barra de cima
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(ci.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text("${index + 1} de ${items.size}" + if (ci.folder.isNotBlank()) "  ·  ${ci.folder}" else "", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    IconButton({ locked = true; chrome = false; hud = "Tela bloqueada" }) { Icon(Icons.Rounded.Lock, "Bloquear tela", tint = Color.White) }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais opções", tint = Color.White) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Detalhes") }, onClick = { menu = false; info = true }, leadingIcon = { Icon(Icons.Rounded.Info, null) })
                            DropdownMenuItem(text = { Text("Restaurar") }, onClick = {
                                menu = false
                                Restore.enqueue(ctx, listOf(ci))
                                hud = "Restaurar na fila"
                            }, leadingIcon = { Icon(Icons.Rounded.Download, null) })
                            DropdownMenuItem(text = { Text("Compartilhar") }, onClick = { menu = false; compartilhar() }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
                            DropdownMenuItem(text = { Text("Capturar quadro") }, onClick = { menu = false; captura() }, leadingIcon = { Icon(Icons.Rounded.Screenshot, null) })
                            val tracks = faixas()
                            if (tracks.isNotEmpty()) {
                                tracks.forEach { (rotulo, fn) ->
                                    DropdownMenuItem(text = { Text("Áudio: $rotulo") }, onClick = { menu = false; fn() })
                                }
                            }
                            DropdownMenuItem(text = { Text("Picture-in-picture") }, onClick = {
                                menu = false
                                PlayerHolder.keepAlive = true
                                if (act?.entrarPip() != true) hud = "PiP indisponível neste aparelho"
                            }, leadingIcon = { Icon(Icons.Rounded.PictureInPictureAlt, null) })
                            DropdownMenuItem(text = { Text(if (bgAudio) "Áudio em 2º plano: ligado" else "Áudio em 2º plano") }, onClick = {
                                menu = false
                                bgAudio = !bgAudio
                                PlayerHolder.backgroundAudio = bgAudio
                                if (bgAudio) { PlayerHolder.keepAlive = true; PlayerHolder.startService(ctx); hud = "Vai continuar tocando em segundo plano" }
                                else { PlayerHolder.stopService(ctx); hud = "Áudio em segundo plano desligado" }
                            }, leadingIcon = { Icon(Icons.Rounded.Headphones, null) })
                            TemporizadorSono.opcoesMin.forEach { m ->
                                DropdownMenuItem(text = { Text("Temporizador: ${TemporizadorSono.rotulo(m)}") }, onClick = {
                                    menu = false; sonoMin = m; sonoInicio = System.currentTimeMillis()
                                    hud = if (m == 0) "Temporizador desligado" else "Dorme em $m min"
                                })
                            }
                            DropdownMenuItem(text = { Text("Apagar da nuvem") }, onClick = { menu = false; confirmDel = true }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // Controles centrais
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ prevIdx?.let { onIndex(it) } }, enabled = prevIdx != null, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Anterior", tint = if (prevIdx != null) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(36.dp))
                    }
                    IconButton({ seekRel(true) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Replay, "−10 s", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    FilledIconButton({
                        val p = player ?: return@FilledIconButton
                        if (p.isPlaying) p.pause() else p.play()
                    }, modifier = Modifier.size(64.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pausar" else "Reproduzir", modifier = Modifier.size(36.dp))
                    }
                    IconButton({ seekRel(false) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Forward10, "+10 s", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    IconButton({ nextIdx?.let { onIndex(it) } }, enabled = nextIdx != null, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Próximo", tint = if (nextIdx != null) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(36.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Barra de baixo: seek + tempo + atalhos — nada sobreposto
                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val d = dur.coerceAtLeast(0L)
                    val frac = if (d > 0) (pos.toFloat() / d).coerceIn(0f, 1f) else 0f
                    val buf = if (d > 0) (buffered.toFloat() / d).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth()) {
                        seekPreview?.let { tpos ->
                            val x = if (d > 0) (tpos.toFloat() / d).coerceIn(0f, 1f) else 0f
                            Surface(Modifier.align(Alignment.TopStart).padding(start = (x * 220).dp, bottom = 4.dp), color = Color.Black.copy(0.8f), shape = RoundedCornerShape(10.dp)) {
                                Text(TempoFmt.fmt(tpos), color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                        LinearProgressIndicator(buf, Modifier.fillMaxWidth().height(3.dp), color = Color.White.copy(alpha = 0.35f), trackColor = Color.White.copy(alpha = 0.15f))
                        Slider(frac, { v -> if (d > 0) { val tpos = (v * d).toLong(); seekPreview = tpos; player?.seekTo(tpos) } }, modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = Nv.Cyan, activeTrackColor = Nv.Cyan, inactiveTrackColor = Color.Transparent), onValueChangeFinished = { seekPreview = null })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(TempoFmt.fmt(pos), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text("  ·  ${TempoFmt.fmt(d)}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                        TextButton({ Restore.enqueue(ctx, listOf(ci)); hud = "Restaurar na fila" }) { Text("Restaurar", color = Color.White) }
                        TextButton({ compartilhar() }) { Text("Enviar", color = Color.White) }
                        Spacer(Modifier.weight(1f))
                        TextButton({ speed = Velocidade.proxima(speed); player?.setPlaybackSpeed(speed); hud = Velocidade.rotulo(speed) }) {
                            Text(Velocidade.rotulo(speed), color = Color.White)
                        }
                        IconButton({
                            loop = loop.proximo()
                            if (loop == ModoRepeticao.AB) {
                                val r = Repeticao.marcarPonto(pontoA, pontoB, player?.currentPosition ?: 0)
                                pontoA = r.first; pontoB = r.second
                                hud = when {
                                    r.second == null -> "Ponto A: ${TempoFmt.fmt(r.first ?: 0)}"
                                    else -> "A-B: ${TempoFmt.fmt(r.first ?: 0)} – ${TempoFmt.fmt(r.second ?: 0)}"
                                }
                            } else { pontoA = null; pontoB = null; hud = loop.rotulo }
                        }) { Icon(Icons.Rounded.Repeat, loop.rotulo, tint = if (loop == ModoRepeticao.DESLIGADO) Color.White.copy(alpha = 0.7f) else Color.White) }
                        IconButton({ aspecto = aspecto.proximo(); hud = aspecto.rotulo }) { Icon(Icons.Rounded.AspectRatio, aspecto.rotulo, tint = Color.White) }
                        IconButton({ orient = orient.proximo(); hud = "Tela: ${orient.rotulo}" }) { Icon(Icons.Rounded.ScreenRotation, orient.rotulo, tint = Color.White) }
                    }
                }
            }
        }
    }

    if (confirmDel) AlertDialog({ confirmDel = false }, icon = { Icon(Icons.Rounded.Warning, null) },
        title = { Text("Apagar da nuvem?") },
        text = { Text("“${ci.name}” será apagado permanentemente do Telegram. Se não houver cópia no celular, ele será perdido para sempre.") },
        confirmButton = { Button({
            confirmDel = false
            scope.launch {
                try { Repo.deleteFromCloud(ci); if (nextIdx != null) onIndex(nextIdx) else if (prevIdx != null) onIndex(prevIdx) else onClose() }
                catch (e: Exception) { hud = "Erro: ${e.message}" }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Apagar") } },
        dismissButton = { TextButton({ confirmDel = false }) { Text("Cancelar") } })

    if (info) {
        val codecV = player?.currentTracks?.groups?.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }?.getTrackFormat(0)?.sampleMimeType
        val codecA = player?.currentTracks?.groups?.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }?.getTrackFormat(0)?.sampleMimeType
        AlertDialog({ info = false }, confirmButton = { TextButton({ info = false }) { Text("OK") } }, title = { Text("Detalhes do vídeo") },
            text = {
                Text(buildString {
                    appendLine(ci.name)
                    appendLine("Pasta: ${ci.folder.ifBlank { "—" }}")
                    appendLine("Data: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(ci.taken))}")
                    appendLine("Tamanho: ${fmtBytes(src?.size ?: ci.size)}")
                    appendLine("Resolução: ${InfoVideo.resolucao(vsize.width, vsize.height)}")
                    appendLine("Duração: ${TempoFmt.fmt(dur)}")
                    if (!codecV.isNullOrBlank()) appendLine("Vídeo: ${codecV.substringAfterLast('/')}")
                    if (!codecA.isNullOrBlank()) appendLine("Áudio: ${codecA.substringAfterLast('/')}")
                    if (ci.path.isNotBlank()) appendLine("Caminho original: ${ci.path}")
                })
            })
    }
}
