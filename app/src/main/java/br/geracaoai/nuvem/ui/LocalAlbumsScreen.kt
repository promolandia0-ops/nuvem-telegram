package br.geracaoai.nuvem.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.work.Backup
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Aba Álbuns com dois lados: o que está na nuvem e os álbuns do celular (pra escolher o que vai). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsTab(actions: @Composable RowScope.() -> Unit, banner: @Composable () -> Unit, onBrowseFolder: (String) -> Unit, onOpen: (List<CloudItem>, Int) -> Unit) {
    var side by rememberSaveable { mutableIntStateOf(0) }
    val seg: @Composable () -> Unit = {
        banner()
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            SegmentedButton(side == 0, { side = 0 }, SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text("Na nuvem") }
            SegmentedButton(side == 1, { side = 1 }, SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text("No celular") }
        }
    }
    if (side == 0) GalleryScreen(null, mode = 2, onBrowseFolder = onBrowseFolder, actions = actions, banner = seg, onOpen = onOpen)
    else LocalAlbumsScreen(actions, seg, onBrowseFolder)
}

private data class LocalAlbum(val name: String, val count: Int, val bytes: Long, val cover: LocalItem?, val inCloud: Int)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalAlbumsScreen(actions: @Composable RowScope.() -> Unit, header: @Composable () -> Unit, onBrowseFolder: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ver by Db.version.collectAsStateWithLifecycle()
    var albums by remember { mutableStateOf<List<LocalAlbum>?>(null) }
    var sel by remember { mutableStateOf(setOf<String>()) }
    val queue by BackupQueue.items.collectAsStateWithLifecycle()
    LaunchedEffect(ver) {
        albums = withContext(Dispatchers.IO) {
            try {
                val all = LocalMedia.all(ctx).filterNot { MediaScan.isThumbPath(it.path) }
                val cloud = Db.allCloudMany(Prefs.galleryChats()).groupingBy { it.folder }.eachCount()
                all.groupBy { it.bucket }.map { (b, l) ->
                    LocalAlbum(b, l.size, l.sumOf { it.size }, l.maxByOrNull { it.taken }, cloud[b] ?: 0)
                }.sortedByDescending { it.bytes }
            } catch (_: Exception) { emptyList() }
        }
    }
    BackHandler(sel.isNotEmpty()) { sel = emptySet() }
    Column(Modifier.fillMaxSize()) {
        if (sel.isEmpty()) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Álbuns", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            actions()
        } else Row(
            Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ sel = emptySet() }) { Icon(Icons.Rounded.Close, "Cancelar seleção") }
            val bytes = albums.orEmpty().filter { it.name in sel }.sumOf { it.bytes }
            Text("${sel.size} • ${fmtBytes(bytes)}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            TextButton({ sel = albums.orEmpty().map { it.name }.toSet() }) { Text("Tudo") }
            Button({
                BackupQueue.addBuckets(sel)
                val n = sel.size; sel = emptySet()
                scope.launch {
                    val busy = withContext(Dispatchers.IO) { Backup.isWorkRunning(ctx).also { Backup.runNow(ctx, true) } }
                    Toast.makeText(ctx, if (busy) "$n ${if (n == 1) "álbum" else "álbuns"} na fila. Sobem logo depois do que está subindo." else "Backup de $n ${if (n == 1) "álbum" else "álbuns"} começou. Acompanhe na faixa lá em cima.", Toast.LENGTH_LONG).show()
                }
            }, contentPadding = PaddingValues(horizontal = 14.dp)) { Icon(Icons.Rounded.CloudUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Fazer backup") }
        }
        header()
        if (sel.isEmpty()) Text("Segure um álbum pra selecionar e fazer backup só dele.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
        val list = albums
        if (list == null) { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
        if (list.isEmpty()) { EmptyCloud("Nenhum álbum no celular", "Libere o acesso a fotos e vídeos."); return }
        LazyVerticalGrid(GridCells.Adaptive(158.dp), Modifier.weight(1f), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.name }) { a ->
                val on = a.name in sel
                val queued = "bucket:${a.name}" in queue
                val missing = (a.count - a.inCloud).coerceAtLeast(0)
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                        .then(if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
                        .combinedClickable(
                            onClick = { if (sel.isNotEmpty()) sel = if (on) sel - a.name else sel + a.name else onBrowseFolder(a.name) },
                            onLongClick = { sel = if (on) sel - a.name else sel + a.name },
                        ).padding(8.dp)
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        a.cover?.let { c ->
                            AsyncImage(ImageRequest.Builder(ctx).data(c.uri).size(300).crossfade(true).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        if (on) Icon(Icons.Rounded.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(folderLabel(a.name), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                    Text("${a.count} itens • ${fmtBytes(a.bytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                    Text(
                        when { queued -> "Na fila do backup"; missing == 0 -> "✓ Tudo na nuvem"; a.inCloud == 0 -> "Nada na nuvem"; else -> "Faltam $missing" },
                        style = MaterialTheme.typography.labelSmall,
                        color = when { queued -> Nv.Gold; missing == 0 -> Nv.Cyan; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}
