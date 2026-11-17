package br.geracaoai.nuvem.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import br.geracaoai.nuvem.data.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val dayFmt = SimpleDateFormat("EEE, d 'de' MMM 'de' yyyy", Locale("pt", "BR"))


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(folder: String?, videosOnly: Boolean = false, onBack: () -> Unit = {}, onOpen: (List<CloudItem>, Int) -> Unit) {
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
    val scope = rememberCoroutineScope()
    val showing = innerFolder
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { try { Repo.reconcileCloud() } catch (_: Exception) {} } }
    LaunchedEffect(ver, showing, videosOnly) {
        loading = true
        all = withContext(Dispatchers.IO) {
            Db.allCloudMany(Prefs.galleryChats()).filter {
                (showing == null || it.folder == showing) && (!videosOnly || it.isVideo)
            }
        }
        loading = false
        selected = emptySet()
    }
    val items = remember(all, query) {
        if (query.isBlank()) all else {
            val q = query.lowercase()
            all.filter { q in it.name.lowercase() || q in dayFmt.format(Date(it.taken)).lowercase() || q in it.folder.lowercase() }
        }
    }
    val albums = remember(items) { items.groupBy { it.folder.ifBlank { "Sem pasta" } }.toList().sortedByDescending { it.second.maxOfOrNull { c -> c.taken } ?: 0L } }
    val daySections = remember(items) { items.groupBy { dayFmt.format(Date(it.taken)) } }
    val selecting = selected.isNotEmpty()
    val folderGrid = showing == null && !videosOnly && query.isBlank()
    val title = when {
        selecting -> "${selected.size} selecionados"
        videosOnly -> "Vídeos"
        showing != null -> folderLabel(showing)
        else -> "Fotos"
    }
    fun goBack() {
        if (selecting) { selected = emptySet(); return }
        if (folder == null && showing != null) { innerFolder = null; return }
        onBack()
    }
    androidx.activity.compose.BackHandler(showing != folder || selecting || videosOnly) { goBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showing != null || videosOnly || (folder == null && showing != null)) {
                // show back when drilled in or videos filter from home
            }
            if (showing != null || videosOnly) IconButton({ goBack() }) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            if (selecting) {
                IconButton({ selected = items.map { it.msgId }.toSet() }) { Icon(Icons.Rounded.SelectAll, "Tudo") }
                IconButton({ selected = emptySet() }) { Icon(Icons.Rounded.Close, "Cancelar") }
            } else if (showing != null) {
                TextButton(enabled = !restoring, onClick = {
                    restoring = true
                    val alb = showing
                    scope.launch {
                        val (ok, total) = try { Repo.restoreAlbum(ctx, alb) } catch (_: Exception) { 0 to items.size }
                        restoreMsg = "Restaurados $ok de $total na pasta original (ou em Nuvem/${folderLabel(alb)})."
                        restoring = false
                    }
                }) { Text(if (restoring) "Restaurando…" else "Restaurar") }
            }
        }
        if (folderGrid || (showing == null && !videosOnly)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Buscar por nome, data ou pasta") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, null) } },
                singleLine = true, shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White.copy(0.06f), focusedContainerColor = Color.White.copy(0.08f)))
        }
        LazyVerticalGrid(
            GridCells.Adaptive(if (folderGrid) 158.dp else 104.dp),
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = if (selecting) 168.dp else 120.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (loading && items.isEmpty()) {
                items(8, key = { "sk$it" }) { ShimmerBlock(Modifier.aspectRatio(1f)) }
            } else if (items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyCloud(if (query.isBlank()) "Nada na nuvem ainda" else "Nada encontrado", if (query.isBlank()) "Escolha pastas no Início e toque em Iniciar." else "Tente outro termo de busca.")
                }
            } else if (folderGrid) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "secret") { SecretAlbumsEntry(onOpen) }
                items(albums, key = { "al${it.first}" }) { (album, albumItems) ->
                    GlassCard(Modifier.animateItem(), onClick = { innerFolder = album }, radius = 20.dp, padding = 8.dp) {
                        Thumb(albumItems.first(), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)))
                        Text(folderLabel(album), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
                        Text("${albumItems.size} itens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                    }
                }
            } else {
                daySections.forEach { (day, list) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h$day") {
                        Text(day.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                    }
                    items(list, key = { it.msgId }) { ci ->
                        val on = ci.msgId in selected
                        Box(Modifier.animateItem().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).combinedClickable(
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
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 96.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xE6151C33)).padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton({
                    restoring = true
                    scope.launch {
                        val chosen = items.filter { it.msgId in selected }
                        var ok = 0
                        chosen.forEach { if (try { Repo.restore(ctx, it) } catch (_: Exception) { false }) ok++ }
                        restoreMsg = "Restaurados $ok de ${chosen.size}."
                        restoring = false; selected = emptySet()
                    }
                }) { Icon(Icons.Rounded.Download, null, tint = Color.White); Spacer(Modifier.width(6.dp)); Text("Restaurar", color = Color.White) }
                TextButton({ confirmDel = true }) { Icon(Icons.Rounded.DeleteOutline, null, tint = Nv.Rose); Spacer(Modifier.width(6.dp)); Text("Apagar", color = Nv.Rose) }
            }
        }
    }
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
    val path by produceState(thumbCache[ci.msgId], ci.msgId) { if (value == null) value = Repo.thumbPath(ci)?.also { thumbCache[ci.msgId] = it } }
    val mini = remember(ci.msgId) { ci.mini?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(0.06f))) {
        if (mini != null) Image(mini, null, Modifier.fillMaxSize().blur(8.dp), contentScale = ContentScale.Crop)
        AnimatedVisibility(path != null, enter = fadeIn()) { AsyncImage(path?.let { File(it) }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (path == null && mini == null) Icon(if (ci.isVideo) Icons.Rounded.Movie else Icons.Rounded.Image, null, Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ci.isVideo) Icon(Icons.Rounded.PlayCircle, null, Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp), tint = Color.White)
    }
}

/** Card estilo Google Fotos: "Liberar armazenamento". Só conta arquivos com hash+tamanho já confirmados na nuvem. */
@Composable
fun FreeUpCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ver by Db.version.collectAsStateWithLifecycle()
    val work by WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(br.geracaoai.nuvem.work.Backup.NOW).collectAsStateWithLifecycle(emptyList())
    val running = work.any { it.state == WorkInfo.State.RUNNING }
    var free by remember { mutableStateOf<Freeable?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(true) }
    LaunchedEffect(ver, running, refresh) {
        if (running) return@LaunchedEffect
        checking = true
        free = withContext(Dispatchers.IO) {
            try { Repo.reconcileCloud() } catch (_: Exception) {}
            try { Repo.freeableQuick(ctx) } catch (_: Exception) { null }
        }
        checking = false
    }
    var phase by remember { mutableStateOf<String?>(null) }  // null, "verify", "confirm"
    var progress by remember { mutableIntStateOf(0) }
    var verified by remember { mutableStateOf<List<LocalItem>>(emptyList()) }
    var result by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        result = if (r.resultCode == Activity.RESULT_OK) "Pronto! ${fmtBytes(verified.sumOf { it.size })} liberados. Tudo continua na sua nuvem." else "Nada foi apagado."
        refresh++
    }
    val f = free
    AnimatedVisibility(f != null && f.items.isNotEmpty() && !running && !checking, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        if (f != null) ElevatedCard(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Rounded.CleaningServices, null, Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Liberar armazenamento", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${f.items.size} itens já salvos no Telegram • ${fmtBytes(f.bytes)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                Button({
                    phase = "verify"; progress = 0
                    scope.launch {
                        verified = Repo.verifyForDelete(f.items) { progress = it }
                        phase = "confirm"
                    }
                }) { Text("Liberar ${fmtBytes(f.bytes)}") }
            }
        }
    }
    if (phase == "verify" && f != null) AlertDialog({}, confirmButton = {}, title = { Text("Verificando backup…") }, text = {
        Column { Text("Conferindo hash e tamanho de cada arquivo com o Telegram (${progress}/${f.items.size})."); Spacer(Modifier.height(12.dp))
            LinearProgressIndicator({ progress / f.items.size.coerceAtLeast(1).toFloat() }, Modifier.fillMaxWidth()) } })
    if (phase == "confirm" && f != null) {
        val skipped = f.items.size - verified.size
        AlertDialog({ phase = null },
            title = { Text(if (verified.isEmpty()) "Nada para liberar" else "Apagar ${verified.size} itens do celular?") },
            text = { Text(if (verified.isEmpty()) "Nenhum arquivo passou na verificação. Nada será apagado." else
                "Serão removidos só deste celular (${fmtBytes(verified.sumOf { it.size })}). As cópias originais continuam no seu Telegram e podem ser restauradas a qualquer momento." +
                (if (skipped > 0) "\n\n$skipped itens não passaram na verificação e NÃO serão apagados." else "")) },
            confirmButton = { if (verified.isNotEmpty()) Button({
                phase = null
                val req = MediaStore.createDeleteRequest(ctx.contentResolver, verified.map { it.uri })
                launcher.launch(IntentSenderRequest.Builder(req.intentSender).build())
            }) { Text("Liberar") } },
            dismissButton = { TextButton({ phase = null }) { Text("Cancelar") } })
    }
    result?.let { AlertDialog({ result = null }, confirmButton = { TextButton({ result = null }) { Text("OK") } }, text = { Text(it) }) }
}
