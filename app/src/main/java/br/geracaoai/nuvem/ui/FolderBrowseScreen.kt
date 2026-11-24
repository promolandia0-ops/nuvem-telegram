package br.geracaoai.nuvem.ui

import android.app.Activity
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.work.Backup
import br.geracaoai.nuvem.work.Restore
import br.geracaoai.nuvem.work.MediaWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowseScreen(
    folder: String,
    onBack: () -> Unit,
    onOpenCloud: (List<CloudItem>, Int) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ver by Db.version.collectAsStateWithLifecycle()
    val work by WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(Backup.NOW).collectAsStateWithLifecycle(emptyList())
    val running = work.any { it.state == WorkInfo.State.RUNNING }
    var all by remember { mutableStateOf<List<FolderMediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(folder, ver, refresh) {
        val first = all.isEmpty()
        if (first) loading = true
        all = withContext(Dispatchers.IO) {
            try { Repo.folderMedia(ctx, folder) } catch (_: Exception) { emptyList() }
        }
        loading = false
        if (first) {
            try { withContext(Dispatchers.IO) { Repo.reconcileCloud() } } catch (_: Exception) {}
            all = withContext(Dispatchers.IO) {
                try { Repo.folderMedia(ctx, folder) } catch (_: Exception) { all }
            }
        }
    }
    FolderBrowseBody(
        folder = folder,
        items = all,
        loading = loading,
        running = running,
        onBack = onBack,
        onOpenCloud = onOpenCloud,
        onVerify = { selected -> Repo.verifyForDelete(selected) { } },
        onTrashed = { refresh++ },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(FeatureTags.PANE_PASTA_LIBERAR),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowseBody(
    folder: String,
    items: List<FolderMediaItem>,
    loading: Boolean,
    running: Boolean,
    onBack: () -> Unit,
    onOpenCloud: (List<CloudItem>, Int) -> Unit,
    onVerify: suspend (List<LocalItem>) -> List<LocalItem>,
    onTrashed: () -> Unit,
    verifyInBody: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(FolderMediaFilter.ALL) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var phase by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var verifyingCount by remember { mutableIntStateOf(0) }
    var verified by remember { mutableStateOf<List<LocalItem>>(emptyList()) }
    var result by remember { mutableStateOf<String?>(null) }
    var typeFilter by remember { mutableStateOf(FolderTypeFilter.ALL) }
    var sort by remember { mutableStateOf(FolderSort.DATE) }
    val shown = remember(items, filter, typeFilter, sort) {
        FolderMedia.applySort(FolderMedia.applyType(FolderMedia.applyFilter(items, filter), typeFilter), sort)
    }
    val selectable = remember(shown) { FolderMedia.selectableIds(shown) }
    val restoreable = remember(shown) { FolderMedia.restoreIds(shown) }
    val (selCount, selBytes) = remember(shown, selected) { FolderMedia.summary(shown, selected) }
    val restoreSel = remember(shown, selected) { shown.filter { it.id in selected && it.kind == FolderItemKind.CLOUD_ONLY }.mapNotNull { it.cloud } }
    val pendingBig = remember(shown) { shown.filter { it.kind == FolderItemKind.LOCAL_PENDING }.sortedByDescending { it.size } }
    val folderBytes = remember(items) { items.filter { it.kind != FolderItemKind.CLOUD_ONLY }.sumOf { it.size } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val bytes = verified.sumOf { it.size }
            ActionLog.add("lixeira", "Moveu ${verified.size} itens já conferidos no Telegram para a lixeira do celular", verified.size, bytes)
            result = "Pronto! ${fmtBytes(bytes)} foram para a lixeira do celular (dá pra recuperar por cerca de 30 dias). Tudo continua na sua nuvem."
            selected = FolderMedia.deselectAll()
            onTrashed()
        } else result = "Nada foi apagado."
    }

    fun startLiberar() {
        val chosen = FolderMedia.selectedLocals(shown, selected)
        if (chosen.isEmpty() || running) return
        if (!verifyInBody) {
            phase = "confirm"
            verified = chosen
            return
        }
        phase = "verify"; progress = 0; verifyingCount = chosen.size
        scope.launch {
            verified = try {
                Repo.verifyForDelete(chosen) { progress = it }
            } catch (_: Exception) {
                try { onVerify(chosen) } catch (_: Exception) { emptyList() }
            }
            phase = "confirm"
        }
    }

    BackHandler { onBack() }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") }
            Column(Modifier.weight(1f)) {
                Text(folderLabel(folder), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val nLocal = items.count { it.kind != FolderItemKind.CLOUD_ONLY }
                val nCloud = items.count { it.kind == FolderItemKind.CLOUD_ONLY }
                Text("$nLocal no aparelho • $nCloud só na nuvem • ${fmtBytes(folderBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (restoreable.isNotEmpty()) {
                TextButton({
                    Restore.enqueue(ctx, shown.mapNotNull { if (it.kind == FolderItemKind.CLOUD_ONLY) it.cloud else null })
                }) { Text("Restaurar pasta") }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == FolderMediaFilter.ALL, { filter = FolderMediaFilter.ALL }, { Text("Todos") }, modifier = Modifier.testTag(FeatureTags.FILTER_TODOS))
            FilterChip(filter == FolderMediaFilter.ON_DEVICE, { filter = FolderMediaFilter.ON_DEVICE }, { Text("No aparelho") }, modifier = Modifier.testTag(FeatureTags.FILTER_APARELHO))
            FilterChip(filter == FolderMediaFilter.CLOUD_ONLY, { filter = FolderMediaFilter.CLOUD_ONLY }, { Text("Só na nuvem") }, modifier = Modifier.testTag(FeatureTags.FILTER_NUVEM))
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(typeFilter == FolderTypeFilter.ALL, { typeFilter = FolderTypeFilter.ALL }, { Text("Tudo") })
            FilterChip(typeFilter == FolderTypeFilter.VIDEOS, { typeFilter = FolderTypeFilter.VIDEOS }, { Text("Vídeos") })
            FilterChip(typeFilter == FolderTypeFilter.PHOTOS, { typeFilter = FolderTypeFilter.PHOTOS }, { Text("Fotos") })
            FilterChip(sort == FolderSort.SIZE, { sort = if (sort == FolderSort.SIZE) FolderSort.DATE else FolderSort.SIZE }, { Text("Maiores") })
        }
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({
                selected = if (filter == FolderMediaFilter.CLOUD_ONLY) FolderMedia.restoreIds(shown) else FolderMedia.selectAll(shown)
            }, enabled = selectable.isNotEmpty() || restoreable.isNotEmpty(), modifier = Modifier.testTag(FeatureTags.BTN_SELECIONAR_TODOS)) { Text("Selecionar todos") }
            TextButton({ selected = FolderMedia.deselectAll() }, enabled = selected.isNotEmpty(), modifier = Modifier.testTag(FeatureTags.BTN_DESMARCAR_TODOS)) { Text("Desmarcar todos") }
            if (pendingBig.isNotEmpty()) {
                TextButton({
                    RestoreStore.priorityPaths = pendingBig.take(20).mapNotNull { it.local?.path }.toSet()
                    Backup.runNow(ctx, true)
                }) { Text("Enviar os maiores") }
            }
        }
        LazyVerticalGrid(
            GridCells.Adaptive(104.dp),
            Modifier.weight(1f).testTag(FeatureTags.GRID_PASTA),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = if (selectable.isNotEmpty() || selected.isNotEmpty()) 176.dp else 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (loading && shown.isEmpty()) {
                items(8, key = { "sk$it" }) { ShimmerBlock(Modifier.aspectRatio(1f)) }
            } else if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyCloud(
                        if (filter == FolderMediaFilter.CLOUD_ONLY) "Nada só na nuvem" else "Pasta vazia",
                        if (filter == FolderMediaFilter.CLOUD_ONLY) "Quando você liberar espaço, os arquivos continuam aparecendo aqui." else "Não achei arquivos nesta pasta.",
                    )
                }
            } else {
                items(shown, key = { it.id }) { item ->
                    FolderMediaCell(
                        item = item,
                        selected = item.id in selected,
                        onClick = {
                            if (item.kind == FolderItemKind.CLOUD_ONLY && selected.isEmpty() && filter != FolderMediaFilter.CLOUD_ONLY) {
                                val openable = shown.mapNotNull { it.cloud }
                                val ci = item.cloud ?: return@FolderMediaCell
                                val idx = openable.indexOfFirst { it.chatId == ci.chatId && it.msgId == ci.msgId }.coerceAtLeast(0)
                                onOpenCloud(openable, idx)
                            } else {
                                selected = FolderMedia.toggleAny(selected, item.id, shown)
                            }
                        },
                    )
                }
            }
        }
        if (selectable.isNotEmpty() || selected.isNotEmpty() || restoreable.isNotEmpty() && filter == FolderMediaFilter.CLOUD_ONLY) {
            Surface(Modifier.fillMaxWidth(), color = Color(0xE6151C33), shadowElevation = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        val nRest = restoreSel.size
                        Text(
                            when {
                                selCount == 0 && nRest == 0 -> "Nenhum selecionado"
                                nRest > 0 && selCount > 0 -> "$selCount p/ liberar • $nRest p/ restaurar"
                                nRest > 0 -> "$nRest ${if (nRest == 1) "item" else "itens"} na nuvem"
                                else -> "$selCount ${if (selCount == 1) "item" else "itens"} • ${fmtBytes(selBytes)}"
                            },
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White,
                        )
                        Text(if (nRest > 0) "Restaura na pasta original, ou em Restaurados." else "Só o que já está no Telegram. Vai pra lixeira do celular.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(0.7f))
                    }
                    if (restoreSel.isNotEmpty()) {
                        Button(onClick = { Restore.enqueue(ctx, restoreSel); selected = FolderMedia.deselectAll() }) { Text("Restaurar") }
                    }
                    if (selCount > 0 || selectable.isNotEmpty()) {
                        Button(
                            onClick = { startLiberar() },
                            enabled = selCount > 0 && !running,
                            modifier = Modifier.testTag(FeatureTags.BTN_LIBERAR_PASTA),
                        ) { Text("Liberar espaço") }
                    }
                }
            }
        }
    }
    if (running && selCount > 0) {
        // button already disabled; hint is in the bar
    }
    if (phase == "verify") AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("Verificando backup…") },
        text = {
            Column {
                Text("Conferindo hash e tamanho de cada arquivo com o Telegram (${progress}/${verifyingCount.coerceAtLeast(1)}).")
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator({ progress / verifyingCount.coerceAtLeast(1).toFloat() }, Modifier.fillMaxWidth())
            }
        },
    )
    if (phase == "confirm") {
        val skipped = FolderMedia.skippedCount(selCount, verified.size)
        AlertDialog(
            { phase = null },
            title = { Text(if (verified.isEmpty()) "Nada para liberar" else "Mover ${verified.size} itens para a lixeira?") },
            text = {
                Text(
                    if (verified.isEmpty()) "Nenhum arquivo passou na verificação. Nada será apagado."
                    else "Vão para a lixeira do celular (${fmtBytes(verified.sumOf { it.size })}), onde dá pra recuperar por cerca de 30 dias. Só saem itens que acabei de conferir no Telegram (arquivo e tamanho). Nada é apagado de vez, e nada some da nuvem." +
                        (if (skipped > 0) "\n\n$skipped itens não passaram na verificação e NÃO serão movidos." else ""),
                )
            },
            confirmButton = {
                if (verified.isNotEmpty()) Button({
                    val toTrash = FolderMedia.allowedToTrash(FolderMedia.selectedLocals(shown, selected), verified)
                    phase = null
                    if (toTrash.isEmpty()) {
                        result = "Nenhum arquivo passou na verificação. Nada será apagado."
                    } else {
                        val sender = SafeTrash.intent(ctx, toTrash.map { it.uri })
                        if (sender != null) launcher.launch(IntentSenderRequest.Builder(sender).build())
                        else result = "Não foi possível abrir a lixeira do celular. Nada foi apagado."
                    }
                }) { Text("Mover para a lixeira") }
            },
            dismissButton = { TextButton({ phase = null }) { Text("Cancelar") } },
        )
    }
    result?.let { AlertDialog({ result = null }, confirmButton = { TextButton({ result = null }) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
private fun FolderMediaCell(item: FolderMediaItem, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
    ) {
        when {
            item.local != null -> LocalThumb(item.local, Modifier.fillMaxSize())
            item.cloud != null -> Thumb(item.cloud, Modifier.fillMaxSize())
            else -> Box(Modifier.fillMaxSize().background(Color.White.copy(0.06f)), contentAlignment = Alignment.Center) {
                Icon(if (item.isVideo) Icons.Rounded.Movie else Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (item.kind != FolderItemKind.LOCAL_PENDING) Text(
            fmtBytes(item.size),
            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 2.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            maxLines = 1,
        )
        if (item.kind == FolderItemKind.CLOUD_ONLY && item.cloud != null && RestoreStore.isRestored(item.cloud.chatId, item.cloud.msgId)) {
            Text(
                "restaurado",
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC0B1224)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Nv.Cyan,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                maxLines = 1,
            )
        } else if (item.kind == FolderItemKind.LOCAL_PENDING) {
            Text(
                "não salvo",
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC1A1020)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Nv.Gold,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        if (item.kind == FolderItemKind.CLOUD_ONLY) {
            Icon(
                Icons.Rounded.Cloud, "Na nuvem",
                Modifier.align(Alignment.TopStart).padding(5.dp).size(22.dp).clip(CircleShape).background(Color(0xAA0B1224)).padding(3.dp),
                tint = Nv.Cyan,
            )
        }
        if (item.isVideo) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayCircle, null, Modifier.size(14.dp), tint = Color.White)
                val dur = fmtDuration(item.durationMs)
                if (dur.isNotEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    Text(dur, color = Color.White, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                }
            }
        }
        if (item.selectable && selected) {
            Icon(Icons.Rounded.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp), tint = Nv.Cyan)
        } else if (item.selectable) {
            Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(16.dp).clip(CircleShape).border(1.5.dp, Color.White.copy(0.85f), CircleShape).background(Color.Black.copy(0.25f)))
        }
    }
}

private val localThumbCache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, android.graphics.Bitmap>(200, 0.75f, true) {
    override fun removeEldestEntry(e: MutableMap.MutableEntry<String, android.graphics.Bitmap>?) = size > 400
})

@Composable
private fun LocalThumb(item: LocalItem, modifier: Modifier) {
    val ctx = LocalContext.current
    val key = item.uri.toString()
    val bmp by produceState(localThumbCache[key], key) {
        if (value == null) value = withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.loadThumbnail(item.uri, Size(280, 280), null).also { localThumbCache[key] = it }
            } catch (_: Exception) { null }
        }
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(0.06f))) {
        if (bmp != null) Image(bmp!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(if (item.mime.startsWith("video/")) Icons.Rounded.Movie else Icons.Rounded.Image, null, Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Lista simples das pastas com backup, entrada do atalho Liberar espaço. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeFoldersSheet(
    onClose: () -> Unit,
    onOpenFolder: (String) -> Unit,
    folders: List<FolderProgress>? = null,
) {
    val ctx = LocalContext.current
    var loaded by remember { mutableStateOf(folders) }
    var loading by remember { mutableStateOf(folders == null) }
    LaunchedEffect(folders) {
        if (folders != null) { loaded = folders; loading = false; return@LaunchedEffect }
        loading = true
        loaded = withContext(Dispatchers.IO) { loadBackupFolders(ctx) }
        loading = false
    }
    val list = (loaded ?: emptyList()).filter { FolderMedia.hasBackup(it.included, it.cloudCount) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Button(
            onClick = { br.geracaoai.nuvem.work.FreeAsk.request.value = true; onClose() },
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp),
        ) { Text("Limpar tudo que já está na nuvem") }
        Text("Apaga do celular tudo que já está no Telegram, de todas as pastas, de uma vez. Antes mostra quanto vai liberar e pede OK ou Cancelar. Confere cada arquivo de novo no Telegram.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        FreeFoldersBody(
            folders = list,
            loading = loading,
            onOpenFolder = { onOpenFolder(it); onClose() },
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).testTag(FeatureTags.PANE_LIBERAR),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun FreeFoldersBody(
    folders: List<FolderProgress>,
    loading: Boolean,
    onOpenFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text("Liberar espaço", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Abra uma pasta com backup pra revisar o que apagar. Nas pastas dá pra ordenar pelos maiores, ver tamanho e restaurar o que está só na nuvem. Só sai do celular o que já está no Telegram, e vai pra lixeira (dá pra recuperar por uns 30 dias).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        when {
            loading && folders.isEmpty() -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Carregando pastas…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            folders.isEmpty() -> Text("Nenhuma pasta com backup ainda. Escolha pastas no Início e toque em Iniciar.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                folders.sortedByDescending { it.localBytes }.forEach { f ->
                    BackupFolderCard(
                        f,
                        Modifier.testTag(FeatureTags.liberarPastaTag(f.name)),
                        onOpen = { onOpenFolder(f.name) },
                        onToggle = {},
                        onSecret = {},
                        showManage = false,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

suspend fun loadBackupFolders(ctx: android.content.Context): List<FolderProgress> = withContext(Dispatchers.IO) {
    val chosen = Prefs.folders + Prefs.secretFolders
    val stats = try { LocalMedia.statsByBucket(ctx) } catch (_: Exception) { emptyMap() }
    val cloud = try { Db.allCloudMany(Prefs.galleryChats()) } catch (_: Exception) { emptyList() }
    val byFolder = cloud.groupBy { it.folder }
    val names = (chosen + stats.keys + byFolder.keys).filter { it.isNotBlank() && it != ORIGINALS_FOLDER && it != WATCHED_FOLDER }.toSet()
    FolderPlan.order(names.toList()).map { name ->
        val st = stats[name]
        val items = byFolder[name].orEmpty()
        FolderProgress(
            name = name, localCount = st?.count ?: 0, localBytes = st?.bytes ?: 0L,
            cloudCount = items.size, cloudBytes = items.sumOf { it.size },
            covers = items.sortedByDescending { it.taken }.take(4),
            included = name in Prefs.folders || name in Prefs.secretFolders,
            secret = name in Prefs.secretFolders,
        )
    }
}
