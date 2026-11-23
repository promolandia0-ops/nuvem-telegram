package br.geracaoai.nuvem.ui

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.work.Backup
import br.geracaoai.nuvem.work.MediaWatch
import br.geracaoai.nuvem.work.Restore
import br.geracaoai.nuvem.work.BatteryGuide
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class FolderProgress(
    val name: String, val localCount: Int, val localBytes: Long,
    val cloudCount: Int, val cloudBytes: Long, val covers: List<CloudItem>,
    val included: Boolean, val secret: Boolean,
) {
    val frac: Float get() = if (localCount <= 0) if (cloudCount > 0) 1f else 0f else (cloudCount.toFloat() / localCount).coerceIn(0f, 1f)
    val done: Boolean get() = included && localCount > 0 && cloudCount >= localCount
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onFotos: (folder: String?, videosOnly: Boolean) -> Unit = { _, _ -> },
    onBrowseFolder: (String) -> Unit = {},
    onSettings: () -> Unit = {},
    onAssistidos: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val wm = WorkManager.getInstance(ctx)
    val now by wm.getWorkInfosForUniqueWorkFlow(Backup.NOW).collectAsStateWithLifecycle(emptyList())
    val per by wm.getWorkInfosForUniqueWorkFlow(Backup.PERIODIC).collectAsStateWithLifecycle(emptyList())
    val cont by wm.getWorkInfosForUniqueWorkFlow(Backup.CONTINUE).collectAsStateWithLifecycle(emptyList())
    var paused by remember { mutableStateOf(Prefs2.backupPaused) }
    val running = if (paused) null else (now + per + cont).firstOrNull { it.state == WorkInfo.State.RUNNING }
    val ver by Db.version.collectAsStateWithLifecycle()
    var folders by remember { mutableStateOf<List<FolderProgress>>(emptyList()) }
    var localBytes by remember { mutableLongStateOf(0L) }
    var cloudBytes by remember { mutableLongStateOf(0L) }
    var localCount by remember { mutableIntStateOf(0) }
    var cloudCount by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var sel by remember { mutableStateOf(Prefs.folders) }
    var auto by remember { mutableStateOf(Prefs.autoBackup) }
    var wifi by remember { mutableStateOf(Prefs.wifiOnly) }
    var charging by remember { mutableStateOf(Prefs.chargingOnly) }
    var origPhotos by remember { mutableStateOf(Prefs.originalPhotos) }
    var instant by remember { mutableStateOf(Prefs.instantBackup) }
    var showFolders by remember { mutableStateOf(false) }
    var showOpts by remember { mutableStateOf(false) }
    var showFree by remember { mutableStateOf(false) }
    var showDups by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    var allBuckets by remember { mutableStateOf<List<Bucket>>(emptyList()) }

    fun save() {
        Prefs.folders = sel; Prefs.autoBackup = auto; Prefs.wifiOnly = wifi
        Prefs.chargingOnly = charging; Prefs.originalPhotos = origPhotos; Prefs.instantBackup = instant
        Backup.schedule(ctx)
    }

    LaunchedEffect(ver, sel, running == null) {
        loading = true
        val data = withContext(Dispatchers.IO) {
            val chosen = Prefs.folders + Prefs.secretFolders
            val stats = try { LocalMedia.statsByBucket(ctx) } catch (_: Exception) { emptyMap() }
            val buckets = try { LocalMedia.buckets(ctx) } catch (_: Exception) { emptyList() }
            val cloud = try { Db.allCloudMany(Prefs.galleryChats()) } catch (_: Exception) { emptyList() }
            val byFolder = cloud.groupBy { it.folder }
            val names = (chosen + stats.keys + byFolder.keys).filter { it.isNotBlank() && it != ORIGINALS_FOLDER && it != WATCHED_FOLDER }.toSet()
            val list = FolderPlan.order(names.toList()).map { name ->
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
            val inc = list.filter { it.included }
            Quad(list, buckets, inc.sumOf { it.localBytes }, inc.sumOf { it.cloudBytes }, inc.sumOf { it.localCount }, inc.sumOf { it.cloudCount })
        }
        folders = data.a; allBuckets = data.b; localBytes = data.c; cloudBytes = data.d; localCount = data.e; cloudCount = data.f
        loading = false
    }

    val workDone = running?.progress?.getInt("done", 0) ?: 0
    val workTotal = running?.progress?.getInt("total", 0) ?: 0
    val workName = running?.progress?.getString("name")
    val frac = when {
        running != null && workTotal > 0 -> workDone / workTotal.toFloat()
        localCount > 0 -> (cloudCount.toFloat() / localCount).coerceIn(0f, 1f)
        cloudCount > 0 -> 1f
        else -> 0f
    }
    val status = when {
        running != null -> "Ativo" to Nv.Cyan
        paused -> "Pausado" to Nv.Gold
        Prefs.lastError.isNotBlank() && !isCancelMessage(Prefs.lastError) -> "Atenção" to Nv.Rose
        localCount > 0 && cloudCount >= localCount -> "Concluído" to Nv.Cyan
        cloudCount > 0 -> "Em dia" to Nv.Sky
        else -> "Pronto" to Nv.Violet
    }
    val heroPct = "${(frac * 100).toInt()}%"
    val heroSub = if (running != null && workTotal > 0) "$workDone de $workTotal" else "enviados"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 108.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "hello") {
            Column {
                Text("Nuvem", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Sua galeria, no seu Telegram.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "hero") {
            GlassCard(radius = 28.dp, padding = 20.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressHero(frac, heroPct, heroSub, running != null)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        StatusChip(status.first, status.second, pulsing = running != null)
                        Spacer(Modifier.height(10.dp))
                        Text(if (running != null) (workName ?: "Fazendo backup…") else if (paused) "Backup pausado" else "Backup", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text("${fmtBytes(cloudBytes)} / ${fmtBytes(localBytes.coerceAtLeast(cloudBytes))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (Prefs.lastBackup > 0 && running == null) {
                            Text("Último: " + SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(Prefs.lastBackup)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (Prefs.lastError.isNotBlank() && running == null && !isCancelMessage(Prefs.lastError)) {
                            Text(traduz(Prefs.lastError), style = MaterialTheme.typography.bodySmall, color = Nv.Rose, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (paused) PillButton("Retomar", Icons.Rounded.PlayArrow, { paused = false; save(); Backup.resume(ctx) }, Modifier.weight(1f), enabled = sel.isNotEmpty() || Prefs.secretFolders.isNotEmpty())
                    else if (running != null) PillButton("Pausar", Icons.Rounded.Pause, { paused = true; Backup.stop(ctx) }, Modifier.weight(1f), filled = false)
                    else PillButton("Iniciar", Icons.Rounded.CloudUpload, { save(); Backup.runNow(ctx, true) }, Modifier.weight(1f), enabled = sel.isNotEmpty() || Prefs.secretFolders.isNotEmpty())
                }
                Spacer(Modifier.height(8.dp))
                TextButton({ MediaWatch.procurarNovosAgora(ctx) }, Modifier.fillMaxWidth(), enabled = sel.isNotEmpty() || Prefs.secretFolders.isNotEmpty()) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Procurar novos agora")
                }
            }
        }
        item(key = "batt") { SamsungBatteryCard() }
        item(key = "fail") { FailedUploadsCard() }
        item(key = "restq") { RestoreQueueCard() }
        item(key = "short") {
            GlassCard(radius = 24.dp, padding = 12.dp) {
                HomeShortcutRow(
                    onFotos = { onFotos(null, false) },
                    onVideos = { onFotos(null, true) },
                    onRestore = { showRestore = true },
                    onFree = { showFree = true },
                    onDups = { showDups = true },
                    onSettings = { onSettings() },
                )
            }
        }
        item(key = "fh") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Pastas", Modifier.weight(1f))
                TextButton({ showFolders = !showFolders }) { Text(if (showFolders) "Recolher" else "Gerenciar") }
            }
        }
        if (loading && folders.isEmpty()) {
            items(3, key = { "sk$it" }) { ShimmerBlock(Modifier.fillMaxWidth().height(88.dp)) }
        }
        itemsIndexed(folders.filter { it.included || showFolders }, key = { _, f -> f.name }) { i, f ->
            BackupFolderCard(f, Modifier.animateItem(fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow)), onOpen = { if (FolderMedia.hasBackup(f.included, f.cloudCount)) onBrowseFolder(f.name) else onFotos(f.name, false) }, onToggle = {
                sel = if (f.name in sel) sel - f.name else sel + f.name; save()
            }, onSecret = {
                val next = if (f.name in Prefs.secretFolders) Prefs.secretFolders - f.name else Prefs.secretFolders + f.name
                Prefs.secretFolders = next
                if (f.name !in sel && f.name in next) { sel = sel + f.name; save() }
            }, showManage = showFolders)
        }
        if (folders.none { it.included } && !loading) {
            item(key = "emptyf") { EmptyCloud("Nenhuma pasta escolhida", "Toque em Gerenciar e escolha Câmera, WhatsApp, Capturas…") }
        }
        item(key = "opts") {
            GlassCard(onClick = { showOpts = !showOpts }, padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Opções do backup", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(if (showOpts) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AnimatedVisibility(showOpts) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        SettingSwitch("Backup automático", "A cada hora, em segundo plano", auto) { auto = it; save() }
                        SettingSwitch("Somente no Wi-Fi", "Não usa dados móveis", wifi) { wifi = it; save() }
                        SettingSwitch("Somente carregando", "Economiza bateria", charging) { charging = it; save() }
                        SettingSwitch("Fotos em qualidade original", "Envia a foto e também o arquivo original", origPhotos) { origPhotos = it; save() }
                        SettingSwitch("Backup instantâneo", "Sobe sozinho quando entra mídia nova", instant) { instant = it; save() }
                        AutoFreeRow()
                    }
                }
            }
        }
    }
    if (showFree) FreeFoldersSheet({ showFree = false }, onOpenFolder = { onBrowseFolder(it) }, folders = folders)
    if (showDups) ModalBottomSheet({ showDups = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.testTag(FeatureTags.PANE_DUPLICADAS).padding(16.dp).navigationBarsPadding()) {
            Text("Duplicadas", style = MaterialTheme.typography.titleLarge)
            Text("Acha fotos, vídeos e originais repetidos no Telegram e apaga, deixando a cópia mais antiga.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            DupCleanRow()
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showRestore) RestoreSheet(folders.filter { it.cloudCount > 0 }.map { it.name }, { showRestore = false })
}

@Composable
fun BackupFolderCard(f: FolderProgress, modifier: Modifier = Modifier, onOpen: () -> Unit, onToggle: () -> Unit, onSecret: () -> Unit, showManage: Boolean) {
    GlassCard(modifier.fillMaxWidth(), onClick = onOpen, radius = 22.dp, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mosaic(f.covers, f.secret)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(folderLabel(f.name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (f.secret) { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp), tint = Nv.Violet) }
                    if (f.done) { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = Nv.Cyan) }
                }
                Text("${f.cloudCount} de ${f.localCount} • ${fmtBytes(f.cloudBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                val bar = if (f.done) Brush.horizontalGradient(listOf(Nv.Cyan, Nv.Sky)) else Nv.Aurora
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(0.08f))) {
                    Box(Modifier.fillMaxWidth(f.frac).fillMaxHeight().clip(CircleShape).background(bar))
                }
            }
            if (showManage) {
                Column {
                    IconButton(onSecret) { Icon(if (f.secret) Icons.Rounded.Lock else Icons.Rounded.LockOpen, "Secreto", tint = if (f.secret) Nv.Violet else MaterialTheme.colorScheme.onSurfaceVariant) }
                    Checkbox(f.included, { onToggle() }, colors = CheckboxDefaults.colors(checkedColor = Nv.Sky))
                }
            }
        }
    }
}

@Composable
private fun Mosaic(covers: List<CloudItem>, secret: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.size(72.dp).clip(shape).background(Color.White.copy(0.06f))) {
        if (covers.isEmpty()) {
            Icon(if (secret) Icons.Rounded.Lock else Icons.Rounded.PhotoLibrary, null, Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (covers.size == 1) {
            Thumb(covers[0], Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize()) {
                covers.take(4).chunked(2).forEach { row ->
                    Row(Modifier.weight(1f)) {
                        row.forEach { ci -> Box(Modifier.weight(1f).fillMaxHeight().padding(0.5.dp)) { Thumb(ci, Modifier.fillMaxSize()) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun FailedUploadsCard() {
    val ctx = LocalContext.current
    val failed by FailStore.items.collectAsStateWithLifecycle()
    if (failed.isEmpty()) return
    GlassCard(radius = 24.dp, padding = 16.dp) {
        Text("Falharam", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Nv.Rose)
        Spacer(Modifier.height(6.dp))
        Text("${failed.size} arquivo${if (failed.size == 1) "" else "s"} não ${if (failed.size == 1) "foi enviado" else "foram enviados"}. Não ${if (failed.size == 1) "conta" else "contam"} como backup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        failed.take(8).forEach { f ->
            Text("• ${f.name}  ${traduz(f.error)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (failed.size > 8) Text("+${failed.size - 8} outros", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        PillButton("Tentar de novo", Icons.Rounded.CloudUpload, { Backup.runNow(ctx, true) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestoreSheet(albums: List<String>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    fun doRestore(name: String) {
        busy = name
        scope.launch {
            try {
                val items = br.geracaoai.nuvem.data.Db.allCloudMany(Prefs.galleryChats() + listOf(Prefs.secretChatId).filter { it != 0L }).filter { it.folder == name }
                Restore.enqueue(ctx, items)
                ActionLog.add("restaurar", "Fila de restauração: ${items.size} em ${folderLabel(name)}", items.size, 0)
                msg = "Comecei a restaurar ${items.size} itens de ${folderLabel(name)}. Posso continuar em segundo plano."
            } catch (e: Exception) {
                msg = e.message ?: "Não deu pra começar a restauração."
            }
            busy = null
        }
    }
    ModalBottomSheet(onClose) {
        Column(Modifier.padding(20.dp).navigationBarsPadding()) {
            Text("Restaurar álbum", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag(FeatureTags.PANE_RESTAURAR))
            Text("Baixa o álbum de volta para a pasta original do celular.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (albums.isEmpty()) Text("Nada na nuvem ainda.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            albums.forEach { name ->
                ListItem(
                    headlineContent = { Text(folderLabel(name)) },
                    trailingContent = { TextButton(enabled = busy == null, onClick = { pending = name }) { Text(if (busy == name) "Restaurando…" else "Restaurar") } },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                )
            }
            msg?.let { Text(it, color = Nv.Cyan, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(12.dp))
        }
    }
    val name = pending
    if (name != null) AlertDialog(
        { pending = null },
        title = { Text("Restaurar ${folderLabel(name)}?") },
        text = { Text("Os arquivos voltam para a pasta original do celular. Nada é apagado da nuvem.") },
        confirmButton = { Button({ pending = null; doRestore(name) }) { Text("Restaurar") } },
        dismissButton = { TextButton({ pending = null }) { Text("Cancelar") } },
    )
}


@Composable
fun HomeShortcutRow(
    onFotos: () -> Unit,
    onVideos: () -> Unit,
    onRestore: () -> Unit,
    onFree: () -> Unit,
    onDups: () -> Unit,
    onSettings: () -> Unit,
) {
    val items = listOf(
        Triple("Fotos", Icons.Rounded.Photo to (Nv.Rose to Color(0xFFE056A0)), onFotos),
        Triple("Vídeos", Icons.Rounded.PlayCircle to (Nv.Sky to Nv.SkyDeep), onVideos),
        Triple("Restaurar", Icons.Rounded.Download to (Nv.Cyan to Color(0xFF0F9E8D)), onRestore),
        Triple("Liberar espaço", Icons.Rounded.CleaningServices to (Nv.Gold to Color(0xFFE08A2C)), onFree),
        Triple("Duplicadas", Icons.Rounded.ContentCopy to (Nv.Violet to Color(0xFF7A5CC8)), onDups),
        Triple("Ajustes", Icons.Rounded.Settings to (Color(0xFF8E9BB8) to Color(0xFF4A5570)), onSettings),
    )
    val tags = listOf(FeatureTags.SHORTCUT_FOTOS, FeatureTags.SHORTCUT_VIDEOS, FeatureTags.SHORTCUT_RESTAURAR, FeatureTags.SHORTCUT_LIBERAR, FeatureTags.SHORTCUT_DUPLICADAS, FeatureTags.SHORTCUT_AJUSTES)
    items.zip(tags).chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEach { (item, tag) ->
                val (label, iconCol, action) = item
                val (icon, cols) = iconCol
                Column(Modifier.weight(1f).testTag(tag).clip(RoundedCornerShape(16.dp)).clickable { action() }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    GlowIcon(icon, cols.first, cols.second, 52.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private data class Quad<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)

@Composable
fun RestoreQueueCard() {
    val ctx = LocalContext.current
    val q by RestoreStore.queue.collectAsStateWithLifecycle()
    if (q.isEmpty()) return
    val pending = q.count { it.state == "pending" || it.state == "running" }
    val done = q.count { it.state == "done" }
    val failed = q.count { it.state == "failed" }
    if (pending == 0 && failed == 0 && done == 0) return
    GlassCard(radius = 24.dp, padding = 16.dp) {
        Text("Restaurar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                if (pending > 0) append("$pending na fila. ")
                if (done > 0) append("$done restaurado${if (done == 1) "" else "s"}. ")
                if (failed > 0) append("$failed falharam.")
            }.trim(),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pending > 0 || failed > 0) PillButton(if (pending > 0) "Continuar" else "Tentar de novo", Icons.Rounded.Download, {
                if (failed > 0) RestoreStore.save(q.map { if (it.state == "failed") it.copy(state = "pending", attempts = 0) else it })
                Restore.start(ctx)
            })
        }
    }
}
