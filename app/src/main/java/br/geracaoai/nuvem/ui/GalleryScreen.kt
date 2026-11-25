package br.geracaoai.nuvem.ui

import br.geracaoai.nuvem.work.Restore

import android.app.Activity
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import br.geracaoai.nuvem.data.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val dayFmt = SimpleDateFormat("EEE, d 'de' MMM 'de' yyyy", Locale("pt", "BR"))
private val shortDate = SimpleDateFormat("dd/MM/yy", Locale("pt", "BR"))

private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
    timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun utcDayToLocalStart(utcMidnight: Long): Long {
    val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
    return Calendar.getInstance().apply {
        clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

/** Intervalo [de, até] do período escolhido. */
private fun periodBounds(period: Int, from: Long, to: Long): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    val today = startOfDay(now)
    return when (period) {
        1 -> today to Long.MAX_VALUE
        2 -> today - 6 * 86_400_000L to Long.MAX_VALUE
        3 -> today - 29 * 86_400_000L to Long.MAX_VALUE
        4 -> Calendar.getInstance().apply { timeInMillis = today; set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis to Long.MAX_VALUE
        5 -> if (from > 0) from to (if (to > 0) to else Long.MAX_VALUE) else Long.MIN_VALUE to Long.MAX_VALUE
        else -> Long.MIN_VALUE to Long.MAX_VALUE
    }
}


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    folder: String?,
    videosOnly: Boolean = false,
    onBack: () -> Unit = {},
    onBrowseFolder: (String) -> Unit = {},
    mode: Int = -1,
    actions: @Composable RowScope.() -> Unit = {},
    banner: @Composable () -> Unit = {},
    onOpen: (List<CloudItem>, Int) -> Unit,
) {
    val ctx = LocalContext.current
    val ver by Db.version.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var innerFolder by remember(folder) { mutableStateOf(folder) }
    var all by remember { mutableStateOf<List<CloudItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmDel by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var restoreMsg by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val showing = innerFolder
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { try { Repo.reconcileCloud() } catch (_: Exception) {} } }
    LaunchedEffect(ver, showing, videosOnly, mode) {
        loading = true
        all = withContext(Dispatchers.IO) {
            Db.allCloudMany(Prefs.galleryChats()).filter {
                (showing == null || DiscoverLogic.normalizeAlbum(it.folder, it.path) == showing) && when (mode) {
                    0 -> it.mime.startsWith("image/")
                    1 -> it.isVideo
                    2 -> it.isVideo || it.mime.startsWith("image/")
                    else -> !videosOnly || it.isVideo
                }
            }
        }
        loading = false
        selected = emptySet()
    }
    // Filtro de data: ordem (recentes/antigos) e período (hoje, 7 dias, 30 dias, este ano, datas escolhidas).
    var oldestFirst by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var period by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) } // 0 tudo,1 hoje,2 7d,3 30d,4 ano,5 escolhido
    var rangeFrom by androidx.compose.runtime.saveable.rememberSaveable { mutableLongStateOf(0L) }
    var rangeTo by androidx.compose.runtime.saveable.rememberSaveable { mutableLongStateOf(0L) }
    var pickRange by remember { mutableStateOf(false) }
    val items = remember(all, query, oldestFirst, period, rangeFrom, rangeTo) {
        val q = query.lowercase()
        val (from, to) = periodBounds(period, rangeFrom, rangeTo)
        val f = all.filter { ci ->
            (query.isBlank() || q in ci.name.lowercase() || q in dayFmt.format(Date(ci.taken)).lowercase() || q in ci.folder.lowercase()) &&
                ci.taken in from..to
        }
        if (oldestFirst) f.sortedWith(compareBy<CloudItem> { it.taken }.thenBy { it.msgId }) else f.sortedWith(compareByDescending<CloudItem> { it.taken }.thenByDescending { it.msgId })
    }
    val albums = remember(items) { DiscoverLogic.groupAlbums(items).map { it.name to it } }
    val daySections = remember(items) { items.groupBy { dayFmt.format(Date(it.taken)) } }
    val selecting = selected.isNotEmpty()
    val folderGrid = showing == null && query.isBlank() && (mode == 2 || (mode == -1 && !videosOnly))
    val title = when {
        selecting -> "${selected.size} selecionados"
        showing != null -> folderLabel(showing)
        mode == 1 || videosOnly -> "Vídeos"
        mode == 2 -> "Álbuns"
        else -> "Fotos"
    }
    fun goBack() {
        if (selecting) { selected = emptySet(); return }
        if (folder == null && showing != null) { innerFolder = null; return }
        onBack()
    }
    androidx.activity.compose.BackHandler(showing != folder || selecting || (mode == -1 && videosOnly)) { goBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showing != null || videosOnly || (folder == null && showing != null)) {
                // show back when drilled in or videos filter from home
            }
            if (showing != null || (mode == -1 && videosOnly)) IconButton({ goBack() }) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            if (selecting) {
                IconButton({ selected = items.map { it.msgId }.toSet() }) { Icon(Icons.Rounded.SelectAll, "Tudo") }
                IconButton({ selected = emptySet() }) { Icon(Icons.Rounded.Close, "Cancelar") }
            } else if (showing != null) {
                TextButton(enabled = !restoring, onClick = { pendingRestore = showing }) { Text(if (restoring) "Restaurando…" else "Restaurar") }
            } else actions()
        }
        if (showing == null && !selecting) banner()
        if (showing == null) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Buscar por nome, data ou pasta") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, null) } },
                singleLine = true, shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White.copy(0.06f), focusedContainerColor = Color.White.copy(0.08f)))
        }
        if (!folderGrid) {
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                item {
                    FilterChip(true, { oldestFirst = !oldestFirst }, { Text(if (oldestFirst) "Mais antigos" else "Recentes") },
                        leadingIcon = { Icon(if (oldestFirst) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, Modifier.size(16.dp)) })
                }
                val names = listOf("Tudo", "Hoje", "7 dias", "30 dias", "Este ano")
                names.forEachIndexed { i, n -> item { FilterChip(period == i, { period = i }, { Text(n) }) } }
                item {
                    FilterChip(period == 5, { pickRange = true }, {
                        Text(if (period == 5 && rangeFrom > 0) "${shortDate.format(Date(rangeFrom))} – ${shortDate.format(Date(rangeTo))}" else "Escolher datas")
                    }, leadingIcon = { Icon(Icons.Rounded.DateRange, null, Modifier.size(16.dp)) })
                }
            }
            if (period != 0 || oldestFirst) Text(
                "${items.size} ${if (items.size == 1) "item" else "itens"}" + (if (period != 0) " no período" else ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 18.dp))
        }
        LazyVerticalGrid(
            GridCells.Adaptive(if (folderGrid) 158.dp else 96.dp),
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = if (selecting) 96.dp else 24.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (folderGrid) 10.dp else 3.dp),
            verticalArrangement = Arrangement.spacedBy(if (folderGrid) 10.dp else 3.dp)
        ) {
            if (loading && items.isEmpty()) {
                items(8, key = { "sk$it" }) { ShimmerBlock(Modifier.aspectRatio(1f)) }
            } else if (items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyCloud(
                        when { period != 0 -> "Nada nesse período"; query.isBlank() -> "Nada na nuvem ainda"; else -> "Nada encontrado" },
                        when { period != 0 -> "Escolha outro período ou toque em Tudo."; query.isBlank() -> "Toque na nuvem lá em cima para ligar o backup."; else -> "Tente outro termo de busca." })
                }
            } else if (folderGrid) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "free") { FreeUpEntry(onBrowseFolder) }
                item(span = { GridItemSpan(maxLineSpan) }, key = "secret") { SecretAlbumsEntry(onOpen) }
                items(albums, key = { "al${it.first}" }) { (album, g) ->
                    GlassCard(Modifier.animateItem(), onClick = { innerFolder = album }, radius = 20.dp, padding = 8.dp) {
                        Thumb(g.items.first(), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)))
                        Text(folderLabel(album), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
                        Text("${g.count} ${if (g.count == 1) "item" else "itens"} • ${fmtBytes(g.bytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                    }
                }
            } else {
                daySections.forEach { (day, list) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h$day") {
                        Text(day.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                    }
                    items(list, key = { it.msgId }) { ci ->
                        val on = ci.msgId in selected
                        Box(Modifier.animateItem().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).combinedClickable(
                            onClick = {
                                if (selecting) selected = if (on) selected - ci.msgId else selected + ci.msgId
                                else onOpen(items, items.indexOf(ci))
                            },
                            onLongClick = { selected = selected + ci.msgId }
                        )) {
                            Thumb(ci, Modifier.fillMaxSize())
                            androidx.compose.animation.AnimatedVisibility(on, modifier = Modifier.align(Alignment.TopEnd), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                                Icon(Icons.Rounded.CheckCircle, null, Modifier.padding(6.dp).size(24.dp).graphicsLayer { }, tint = Nv.Cyan)
                            }
                            if (selecting && !on) {
                                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp).clip(CircleShape).background(Color.Black.copy(0.35f)).border(1.5.dp, Color.White.copy(0.8f), CircleShape))
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(selecting, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xE6151C33)).padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton({
                    val chosen = items.filter { it.msgId in selected }
                    Restore.enqueue(ctx, chosen)
                    restoreMsg = "${chosen.size} ${if (chosen.size == 1) "item" else "itens"} na fila pra restaurar."
                    selected = emptySet()
                }) { Icon(Icons.Rounded.Download, null, tint = Color.White); Spacer(Modifier.width(6.dp)); Text("Restaurar", color = Color.White) }
                TextButton({ confirmDel = true }) { Icon(Icons.Rounded.DeleteOutline, null, tint = Nv.Rose); Spacer(Modifier.width(6.dp)); Text("Apagar", color = Nv.Rose) }
            }
        }
    }
    if (pickRange) {
        val st = rememberDateRangePickerState(
            initialSelectedStartDateMillis = rangeFrom.takeIf { it > 0 }, initialSelectedEndDateMillis = rangeTo.takeIf { it > 0 })
        DatePickerDialog(
            onDismissRequest = { pickRange = false },
            confirmButton = { TextButton(enabled = st.selectedStartDateMillis != null, onClick = {
                val a = st.selectedStartDateMillis ?: return@TextButton
                val b = st.selectedEndDateMillis ?: a
                // O calendário devolve meia-noite UTC; ajusta para o dia inteiro no fuso do celular.
                rangeFrom = utcDayToLocalStart(a); rangeTo = utcDayToLocalStart(b) + 86_400_000L - 1
                period = 5; pickRange = false
            }) { Text("Filtrar") } },
            dismissButton = { TextButton({ pickRange = false; if (period == 5) period = 0 }) { Text("Limpar") } },
        ) {
            DateRangePicker(st, Modifier.heightIn(max = 520.dp), title = { Text("Escolha o período", Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }
    val alb = pendingRestore
    if (alb != null) AlertDialog(
        { pendingRestore = null },
        title = { Text("Restaurar ${folderLabel(alb)}?") },
        text = { Text("Os arquivos voltam para a pasta original do celular. Nada é apagado da nuvem.") },
        confirmButton = { Button({
            pendingRestore = null
            Restore.enqueue(ctx, items)
            ActionLog.add("restaurar", "Fila pra restaurar ${items.size} em ${folderLabel(alb)}", 0, 0)
            restoreMsg = "${items.size} itens na fila pra restaurar na pasta original (ou em Restaurados)."
        }) { Text("Restaurar") } },
        dismissButton = { TextButton({ pendingRestore = null }) { Text("Cancelar") } },
    )
    restoreMsg?.let { msg -> AlertDialog({ restoreMsg = null }, confirmButton = { TextButton({ restoreMsg = null }) { Text("OK") } }, text = { Text(msg) }) }
    if (confirmDel) AlertDialog({ confirmDel = false }, title = { Text("Apagar ${selected.size} itens da nuvem?") },
        text = { Text("Eles serão apagados permanentemente do Telegram. Se não houver cópia no celular, serão perdidos para sempre.") },
        confirmButton = { Button({
            val ids = selected
            confirmDel = false; selected = emptySet()
            scope.launch {
                items.filter { it.msgId in ids }.forEach { try { Repo.deleteFromCloud(it) } catch (_: Exception) {} }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Apagar") } },
        dismissButton = { TextButton({ confirmDel = false }) { Text("Cancelar") } })
}

private val thumbCache = Collections.synchronizedMap(object : LinkedHashMap<Long, String>(500, 0.75f, true) {
    override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, String>?) = size > 2000 })

@Composable
fun Thumb(ci: CloudItem, modifier: Modifier) {
    val ctx = LocalContext.current
    val path by produceState(thumbCache[ci.msgId], ci.msgId) {
        if (value == null) value = withContext(Dispatchers.IO) { Repo.thumbPath(ci)?.also { thumbCache[ci.msgId] = it } }
    }
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        AnimatedVisibility(path != null, enter = fadeIn()) {
            val req = ImageRequest.Builder(ctx).data(path?.let { File(it) }).size(280).crossfade(true).build()
            AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (path == null) Icon(if (ci.isVideo) Icons.Rounded.Movie else Icons.Rounded.Image, null, Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ci.isVideo) Icon(Icons.Rounded.PlayCircle, null, Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp), tint = Color.White)
    }
}

/** Atalho compacto na grade de álbuns: abre a lista de pastas com backup. */
@Composable
fun FreeUpEntry(onBrowseFolder: (String) -> Unit, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    val freeSpace = rememberFreeSpace()
    GlassCard(modifier.fillMaxWidth(), onClick = { freeSpace() }, radius = 20.dp, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CleaningServices, null, tint = Nv.Gold)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Liberar espaço", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Fotos, vídeos e arquivos que já estão na nuvem saem do celular. Mostra os GB antes de apagar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (show) FreeFoldersSheet({ show = false }, onOpenFolder = onBrowseFolder)
}

/** Tela de Liberar espaço: lista das pastas com backup. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeUpSheet(onClose: () -> Unit, onOpenFolder: (String) -> Unit = {}, folders: List<FolderProgress>? = null) {
    FreeFoldersSheet(onClose, onOpenFolder, folders)
}

/** Card/tela de "Liberar espaço". Só conta arquivos com hash+tamanho já confirmados na nuvem. Nunca fica em branco. */
@Composable
fun FreeUpCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ver by Db.version.collectAsStateWithLifecycle()
    val work by WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(br.geracaoai.nuvem.work.Backup.NOW).collectAsStateWithLifecycle(emptyList())
    val running = work.any { it.state == WorkInfo.State.RUNNING }
    var free by remember { mutableStateOf<Freeable?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(true) }
    LaunchedEffect(ver, running, refresh) {
        if (running) { checking = false; return@LaunchedEffect }
        checking = true
        free = withContext(Dispatchers.IO) {
            try { Repo.reconcileCloud() } catch (_: Exception) {}
            try { Repo.freeableQuick(ctx) } catch (_: Exception) { null }
        }
        checking = false
    }
    var cleanup by remember { mutableStateOf<List<LocalItem>?>(null) }
    CleanupFlow(cleanup) { _ -> cleanup = null; refresh++ }
    var phase by remember { mutableStateOf<String?>(null) }  // null, "verify", "confirm"
    var progress by remember { mutableIntStateOf(0) }
    var verified by remember { mutableStateOf<List<LocalItem>>(emptyList()) }
    var result by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val bytes = verified.sumOf { it.size }
            ActionLog.add("lixeira", "Moveu ${verified.size} itens já conferidos no Telegram para a lixeira do celular", verified.size, bytes)
            result = "Pronto! ${fmtBytes(bytes)} foram para a lixeira do celular (dá pra recuperar por cerca de 30 dias). Tudo continua na sua nuvem."
        } else result = "Nada foi apagado."
        refresh++
    }
    val f = free
    FreeUpBody(
        checking = checking, running = running,
        itemCount = f?.items?.size ?: 0, bytes = f?.bytes ?: 0L,
        onLiberar = { cleanup = f?.items },
        modifier = modifier,
    )
    if (phase == "verify" && f != null) AlertDialog({}, confirmButton = {}, title = { Text("Verificando backup…") }, text = {
        Column { Text("Conferindo hash e tamanho de cada arquivo com o Telegram (${progress}/${f.items.size})."); Spacer(Modifier.height(12.dp))
            LinearProgressIndicator({ progress / f.items.size.coerceAtLeast(1).toFloat() }, Modifier.fillMaxWidth()) } })
    if (phase == "confirm" && f != null) {
        val skipped = f.items.size - verified.size
        AlertDialog({ phase = null },
            title = { Text(if (verified.isEmpty()) "Nada para liberar" else "Mover ${verified.size} itens para a lixeira?") },
            text = { Text(if (verified.isEmpty()) "Nenhum arquivo passou na verificação. Nada será apagado." else
                "Vão para a lixeira do celular (${fmtBytes(verified.sumOf { it.size })}), onde dá pra recuperar por cerca de 30 dias. Só saem itens que acabei de conferir no Telegram (arquivo e tamanho). Nada é apagado de vez, e nada some da nuvem." +
                (if (skipped > 0) "\n\n$skipped itens não passaram na verificação e NÃO serão movidos." else "")) },
            confirmButton = { if (verified.isNotEmpty()) Button({
                phase = null
                val sender = SafeTrash.intent(ctx, verified.map { it.uri })
                if (sender != null) launcher.launch(IntentSenderRequest.Builder(sender).build())
                else result = "Não foi possível abrir a lixeira do celular. Nada foi apagado."
            }) { Text("Mover para a lixeira") } },
            dismissButton = { TextButton({ phase = null }) { Text("Cancelar") } })
    }
    result?.let { AlertDialog({ result = null }, confirmButton = { TextButton({ result = null }) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
fun FreeUpBody(
    checking: Boolean,
    running: Boolean,
    itemCount: Int,
    bytes: Long,
    onLiberar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag(FeatureTags.PANE_LIBERAR)) {
        Text("Liberar espaço", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Só entram arquivos que já conferi no Telegram. Eles vão para a lixeira do celular (dá pra recuperar por uns 30 dias). Nada some da nuvem.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        when {
            running -> Text("Espere o backup terminar pra liberar espaço.", style = MaterialTheme.typography.bodyMedium)
            checking -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Procurando arquivos já salvos no Telegram…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            itemCount <= 0 -> Text("Nada pra liberar agora. Só aparece o que já está no seu Telegram com o mesmo arquivo e tamanho.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                Text("$itemCount itens já salvos no Telegram • ${fmtBytes(bytes)}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onLiberar) { Text("Liberar ${fmtBytes(bytes)}") }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
