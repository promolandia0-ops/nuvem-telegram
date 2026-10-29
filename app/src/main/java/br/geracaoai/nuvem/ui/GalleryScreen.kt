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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
fun GalleryScreen(folder: String?, onOpen: (List<CloudItem>, Int) -> Unit) {
    val ver by Db.version.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<CloudItem>>(emptyList()) }
    LaunchedEffect(ver, folder) { all = withContext(Dispatchers.IO) { Db.allCloud(Prefs.chatId).filter { folder == null || it.folder == folder } } }
    val items = remember(all, query) {
        if (query.isBlank()) all else { val q = query.lowercase(); all.filter { q in it.name.lowercase() || q in dayFmt.format(Date(it.taken)).lowercase() || q in it.folder.lowercase() } }
    }
    val sections = remember(items) { items.groupBy { dayFmt.format(Date(it.taken)) } }
    Column(Modifier.fillMaxSize()) {
        if (folder == null) {
            SearchBar(query, { query = it }, {}, false, {}, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Buscar por nome, data ou pasta") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, null) } }) {}
        }
        LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (folder == null) item(span = { GridItemSpan(maxLineSpan) }, key = "free") { FreeUpCard() }
            if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text(if (query.isBlank()) "Nada na nuvem ainda.\nEscolha pastas em Backup para começar." else "Nada encontrado.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            sections.forEach { (day, list) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "h$day") {
                    Text(day.replaceFirstChar { it.uppercase() }, Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                items(list, key = { it.msgId }) { ci ->
                    Thumb(ci, Modifier.animateItem().aspectRatio(1f).clickable { onOpen(items, items.indexOf(ci)) })
                }
            }
        }
    }
}

private val thumbCache = Collections.synchronizedMap(object : LinkedHashMap<Long, String>(500, 0.75f, true) {
    override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, String>?) = size > 2000 })

@Composable
fun Thumb(ci: CloudItem, modifier: Modifier) {
    val path by produceState(thumbCache[ci.msgId], ci.msgId) { if (value == null) value = Repo.thumbPath(ci)?.also { thumbCache[ci.msgId] = it } }
    val mini = remember(ci.msgId) { ci.mini?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
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
    LaunchedEffect(ver, running, refresh) { if (!running) free = withContext(Dispatchers.IO) { try { Repo.freeableQuick(ctx) } catch (_: Exception) { null } } }
    var phase by remember { mutableStateOf<String?>(null) }  // null, "verify", "confirm"
    var progress by remember { mutableIntStateOf(0) }
    var verified by remember { mutableStateOf<List<LocalItem>>(emptyList()) }
    var result by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        result = if (r.resultCode == Activity.RESULT_OK) "Pronto! ${fmtBytes(verified.sumOf { it.size })} liberados. Tudo continua na sua nuvem." else "Nada foi apagado."
        refresh++
    }
    val f = free
    AnimatedVisibility(f != null && f.items.isNotEmpty() && !running, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
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
