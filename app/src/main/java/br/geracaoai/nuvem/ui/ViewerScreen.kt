package br.geracaoai.nuvem.ui

import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import br.geracaoai.nuvem.data.CloudItem
import br.geracaoai.nuvem.data.Repo
import br.geracaoai.nuvem.tg.TdDataSource
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(items: List<CloudItem>, start: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(start) { items.size }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(true) }
    var confirmDel by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val cur = items.getOrNull(pager.currentPage)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items[it].msgId }) { p ->
            val ci = items[p]
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { chrome = !chrome }, contentAlignment = Alignment.Center) {
                if (ci.isVideo) VideoPage(ci, pager.currentPage == p) else PhotoPage(ci)
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(title = { Text(cur?.name ?: "", maxLines = 1) },
                    navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = .4f), titleContentColor = Color.White, navigationIconContentColor = Color.White),
                    modifier = Modifier.statusBarsPadding())
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .4f)).navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ViewerAction(Icons.Rounded.Download, "Restaurar") { cur?.let { c -> scope.launch { snack.showSnackbar("Baixando…"); snack.showSnackbar(if (try { Repo.restore(ctx, c) } catch (_: Exception) { false }) "Salvo em Imagens/Vídeos › Nuvem" else "Falha ao restaurar") } } }
                    ViewerAction(Icons.Rounded.Info, "Detalhes") { info = true }
                    ViewerAction(Icons.Rounded.DeleteOutline, "Apagar") { confirmDel = true }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }
    if (confirmDel && cur != null) AlertDialog({ confirmDel = false }, icon = { Icon(Icons.Rounded.Warning, null) },
        title = { Text("Apagar da nuvem?") },
        text = { Text("“${cur.name}” será apagado permanentemente do Telegram. Se não houver cópia no celular, ele será perdido para sempre.") },
        confirmButton = { Button({ confirmDel = false; scope.launch { try { Repo.deleteFromCloud(cur); if (items.size <= 1) onClose() } catch (e: Exception) { snack.showSnackbar("Erro: ${e.message}") } } },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Apagar") } },
        dismissButton = { TextButton({ confirmDel = false }) { Text("Cancelar") } })
    if (info && cur != null) AlertDialog({ info = false }, confirmButton = { TextButton({ info = false }) { Text("OK") } }, title = { Text("Detalhes") },
        text = { Text("Nome: ${cur.name}\nData: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(cur.taken))}\nTamanho: ${fmtBytes(cur.size)}\nPasta: ${cur.folder}\nCaminho original: ${cur.path}\nSHA-256: ${cur.hash.take(16)}…") })
}

@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = Color.White); Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PhotoPage(ci: CloudItem) {
    val thumb by produceState<String?>(null, ci.msgId) { value = Repo.thumbPath(ci) }
    val full by produceState<String?>(null, ci.msgId) { value = try { Repo.fullPath(ci) } catch (_: Exception) { null } }
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); off = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else off + pan }
    }, contentAlignment = Alignment.Center) {
        val m = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y)
        thumb?.let { AsyncImage(File(it), null, m, contentScale = ContentScale.Fit) }
        Crossfade(full, label = "full") { f -> if (f != null) AsyncImage(File(f), ci.name, m, contentScale = ContentScale.Fit) }
        if (full == null) CircularProgressIndicator(Modifier.size(32.dp), color = Color.White)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(ci: CloudItem, active: Boolean) {
    val ctx = LocalContext.current
    val doc by produceState<org.drinkless.tdlib.TdApi.Document?>(null, ci.msgId) { value = try { Repo.docOf(Repo.message(ci)) } catch (_: Exception) { null } }
    val d = doc
    if (d == null || !active) { Thumb(ci, Modifier.fillMaxWidth().aspectRatio(1f)); return }
    val player = remember(ci.msgId) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory(TdDataSource.Factory(d.document.id, d.document.size))
                .createMediaSource(MediaItem.fromUri("tdlib://file/${d.document.id}")))
            prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView({ PlayerView(it).apply { this.player = player } }, Modifier.fillMaxSize())
}
