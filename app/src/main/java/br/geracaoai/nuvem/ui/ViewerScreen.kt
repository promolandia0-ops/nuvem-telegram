package br.geracaoai.nuvem.ui

import br.geracaoai.nuvem.work.Restore

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import br.geracaoai.nuvem.data.CloudItem
import br.geracaoai.nuvem.data.Repo
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
    var idx by remember { mutableIntStateOf(start) }
    var chrome by remember { mutableStateOf(true) }
    var confirmDel by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val cur = items.getOrNull(idx)
    val curIsVideo = cur?.isVideo == true
    fun goTo(i: Int) {
        if (i !in items.indices) return
        idx = i
        if (items[i].isVideo.not()) scope.launch { try { pager.scrollToPage(i) } catch (_: Exception) {} }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (curIsVideo && cur != null) {
            VideoPlayer(items, idx, { i -> goTo(i) }, onClose)
        } else {
        androidx.compose.runtime.LaunchedEffect(idx) { if (items.getOrNull(idx)?.isVideo != true && pager.currentPage != idx) try { pager.scrollToPage(idx) } catch (_: Exception) {} }
        androidx.compose.runtime.LaunchedEffect(pager.currentPage) { if (items.getOrNull(pager.currentPage)?.isVideo != true) idx = pager.currentPage }
        // fotos vizinhas já vão baixando
        androidx.compose.runtime.LaunchedEffect(pager.currentPage) {
            for (d in listOf(1, -1, 2)) items.getOrNull(pager.currentPage + d)?.let { launch { Repo.prefetchView(it) } }
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items[it].msgId }, beyondViewportPageCount = 1) { p ->
            val ci = items[p]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (ci.isVideo) Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { chrome = !chrome })
                else PhotoPage(ci) { chrome = !chrome }
            }
        }
        }
        AnimatedVisibility(chrome && !curIsVideo, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(title = { Text(cur?.name ?: "", maxLines = 1) },
                    navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = .4f), titleContentColor = Color.White, navigationIconContentColor = Color.White),
                    modifier = Modifier.statusBarsPadding())
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .4f)).navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ViewerAction(Icons.Rounded.Download, "Restaurar") { cur?.let { c -> Restore.enqueue(ctx, listOf(c)); scope.launch { snack.showSnackbar("Restaurar na fila") } } }
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
private fun PhotoPage(ci: CloudItem, onTap: () -> Unit) {
    val thumb by produceState<String?>(null, ci.msgId) { value = Repo.thumbDisk(ci).takeIf { it.length() > 0 }?.path ?: Repo.thumbPath(ci) }
    val full by produceState<String?>(null, ci.msgId) { value = try { Repo.viewPath(ci) } catch (_: Exception) { null } }
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Box(Modifier.fillMaxSize()
        .pointerInput(Unit) {
            detectTapGestures(onTap = { onTap() }, onDoubleTap = {
                if (scale > 1f) { scale = 1f; off = androidx.compose.ui.geometry.Offset.Zero } else scale = 2.5f
            })
        }
        .pointerInput(Unit) {
            // Zoom com dois dedos; arrastar só move a foto quando ela está ampliada.
            // Com a foto normal, o arrastar com um dedo fica livre pra passar pra próxima.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val ev = awaitPointerEvent()
                    if (ev.changes.size > 1 || scale > 1f) {
                        scale = (scale * ev.calculateZoom()).coerceIn(1f, 6f)
                        off = if (scale <= 1f) androidx.compose.ui.geometry.Offset.Zero else off + ev.calculatePan()
                        ev.changes.forEach { it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
            }
        }, contentAlignment = Alignment.Center) {
        val m = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y)
        if (full == null) thumb?.let { AsyncImage(File(it), null, m, contentScale = ContentScale.Fit) }
        full?.let { f -> AsyncImage(File(f), ci.name, m, contentScale = ContentScale.Fit) }
        if (full == null) CircularProgressIndicator(Modifier.size(32.dp), color = Color.White)
    }
}
