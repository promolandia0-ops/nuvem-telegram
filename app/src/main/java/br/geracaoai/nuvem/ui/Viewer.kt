package br.geracaoai.nuvem.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import br.geracaoai.nuvem.media.Thumbs
import br.geracaoai.nuvem.tg.CloudRec
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Visualizador em tela cheia: desliza entre fotos e vídeos, dá zoom e toca vídeo. */
@Composable
fun ViewerScreen(lista: List<CloudRec>, inicio: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = inicio, pageCount = { lista.size })
    var barras by remember { mutableStateOf(true) }
    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            key = { lista[it].msgId },
        ) { pagina ->
            val foto = lista[pagina]
            if (foto.meta.isVideo) {
                VideoPage(foto, ativo = pager.currentPage == pagina)
            } else {
                PhotoPage(foto, onTap = { barras = !barras })
            }
        }

        val atual = lista[pager.currentPage]
        AnimatedVisibility(
            visible = barras,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.ArrowBack, "Voltar", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        atual.meta.name,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        dataCompleta(atual.meta.taken),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoPage(foto: CloudRec, onTap: () -> Unit) {
    val bmp by produceState<Bitmap?>(initialValue = null, foto.msgId) { value = Thumbs.full(foto) }
    var escala by remember { mutableStateOf(1f) }
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (escala > 1f) {
                            escala = 1f
                            dx = 0f
                            dy = 0f
                        } else {
                            escala = 2.5f
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    escala = (escala * zoom).coerceIn(1f, 5f)
                    if (escala > 1f) {
                        dx += pan.x
                        dy += pan.y
                    } else {
                        dx = 0f
                        dy = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = foto.meta.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = escala
                        scaleY = escala
                        translationX = dx
                        translationY = dy
                    },
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun VideoPage(video: CloudRec, ativo: Boolean) {
    val caminho by produceState<String?>(initialValue = null, video.msgId) {
        value = Thumbs.videoPath(video) ?: ""
    }
    val c = caminho
    when {
        c == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("Baixando o vídeo do Telegram…", color = Color.White)
            }
        }
        c.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Não consegui abrir este vídeo.", color = Color.White)
        }
        else -> PlayerBody(c, ativo)
    }
}

@Composable
private fun PlayerBody(caminho: String, ativo: Boolean) {
    val ctx = LocalContext.current
    val exo = remember(caminho) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(caminho))))
            prepare()
        }
    }
    DisposableEffect(exo) {
        onDispose { exo.release() }
    }
    LaunchedEffect(exo, ativo) {
        exo.playWhenReady = ativo
    }
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                player = exo
                useController = true
            }
        },
        update = { view -> view.player = exo },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun dataCompleta(ms: Long): String =
    if (ms <= 0L) "Sem data" else SimpleDateFormat("d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(ms))
