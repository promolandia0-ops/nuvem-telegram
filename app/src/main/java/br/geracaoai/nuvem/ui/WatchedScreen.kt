package br.geracaoai.nuvem.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.shizuku.ShizState
import br.geracaoai.nuvem.shizuku.Shiz
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.work.WatchedSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import rikka.shizuku.Shizuku
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WatchedScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val wm = WorkManager.getInstance(ctx)
    val ver by Db.version.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<CloudItem>>(emptyList()) }
    LaunchedEffect(ver) { items = withContext(Dispatchers.IO) { Db.watchedCloud(Prefs.chatId) } }
    var shiz by remember { mutableStateOf(Shiz.state()) }
    var tree by remember { mutableStateOf(Prefs2.watchedTree) }
    var paused by remember { mutableStateOf(Prefs2.watchedPaused) }
    DisposableEffect(Unit) {
        val l = Shizuku.OnRequestPermissionResultListener { _, _ -> shiz = Shiz.state(); WatchedSync.runNow(ctx, true) }
        val b = Shizuku.OnBinderReceivedListener { shiz = Shiz.state() }
        Shizuku.addRequestPermissionResultListener(l); Shizuku.addBinderReceivedListenerSticky(b)
        onDispose { Shizuku.removeRequestPermissionResultListener(l); Shizuku.removeBinderReceivedListener(b) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { shiz = Shiz.state(); WatchedSync.runNow(ctx) }
    val now by wm.getWorkInfosForUniqueWorkFlow(WatchedSync.NOW).collectAsStateWithLifecycle(emptyList())
    val per by wm.getWorkInfosForUniqueWorkFlow(WatchedSync.PERIODIC).collectAsStateWithLifecycle(emptyList())
    val running = if (paused) null else (now + per).firstOrNull { it.state == WorkInfo.State.RUNNING }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            Prefs2.watchedTree = u.toString(); tree = u.toString(); WatchedSync.runNow(ctx, true) }
    }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmDel by remember { mutableStateOf(false) }
    var alsoLocal by remember { mutableStateOf(true) }
    var opening by remember { mutableStateOf<Pair<CloudItem, Int>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun play(ci: CloudItem) = scope.launch {
        opening = ci to 0
        try {
            val m = Repo.message(ci); val f = Repo.docOf(m)?.document ?: throw Exception("Arquivo não encontrado")
            val job = launch { Td.fileUpdates.collect { u -> if (u.id == f.id && u.expectedSize > 0) opening = ci to (u.local.downloadedSize * 100 / u.expectedSize).toInt() } }
            val path = try { Td.send(TdApi.DownloadFile(f.id, 32, 0, 0, true)).local.path } finally { job.cancel() }
            if (opening == null) return@launch
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(path))
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, ci.mime.ifBlank { "video/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(view, "Abrir com…").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { msg = "Não deu para abrir: ${e.message}" }
        opening = null
    }

    Column(Modifier.fillMaxSize()) {
        AnimatedContent(selected.isNotEmpty(), label = "top") { sel ->
            if (sel) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ selected = emptySet() }) { Icon(Icons.Rounded.Close, null) }
                Text("${selected.size} selecionados", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton({ selected = items.map { it.msgId }.toSet() }) { Icon(Icons.Rounded.SelectAll, null) }
                IconButton({ confirmDel = true }) { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) }
            } else Text("Assistidos no Telegram", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.headlineSmall)
        }
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "status") {
                ElevatedCard(Modifier.fillMaxWidth().padding(4.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (running != null) Icons.Rounded.CloudSync else Icons.Rounded.OndemandVideo, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(if (running != null) "Sincronizando…" else if (paused) "Sincronização parada" else "${items.size} vídeos na nuvem", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(6.dp))
                        AnimatedContent(running, label = "wp") { r ->
                            if (r != null) Column {
                                val d = r.progress.getInt("done", 0); val t = r.progress.getInt("total", 0)
                                Text(if (t == 0) "Procurando vídeos no cache do Telegram…" else "${d + 1} de $t • ${r.progress.getString("name") ?: ""}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(6.dp)); LinearProgressIndicator({ r.progress.getInt("pct", 0) / 100f }, Modifier.fillMaxWidth())
                            } else Text("Os vídeos que você assiste no Telegram são copiados para cá a cada 15 min e quando você abre o app." +
                                (if (Prefs2.watchedError.isNotBlank()) "\nÚltimo erro: ${traduz(Prefs2.watchedError)}" else ""),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ paused = false; WatchedSync.runNow(ctx, true) }, enabled = running == null) { Icon(Icons.Rounded.Sync, null); Spacer(Modifier.width(6.dp)); Text("Sincronizar agora") }
                            if (running != null) OutlinedButton({ paused = true; WatchedSync.stop(ctx) }) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text("Parar") }
                        }
                    }
                }
            }
            if (shiz != ShizState.READY) item(span = { GridItemSpan(maxLineSpan) }, key = "shiz") {
                ShizukuCard(shiz, tree.isNotBlank(), onGrant = { Shiz.requestPermission() }, onPickTree = {
                    pickTree.launch(Uri.parse("content://com.android.externalstorage.documents/document/primary%3ATelegram")) })
            }
            if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("Nenhum vídeo assistido ainda.\nAssista algo no Telegram e toque em Sincronizar agora.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(items, key = { it.msgId }) { ci ->
                val isSel = ci.msgId in selected
                Box(Modifier.animateItem().aspectRatio(1f).combinedClickable(
                    onClick = { if (selected.isNotEmpty()) selected = if (isSel) selected - ci.msgId else selected + ci.msgId else play(ci) },
                    onLongClick = { selected = selected + ci.msgId })) {
                    Thumb(ci, Modifier.fillMaxSize())
                    Icon(Icons.Rounded.PlayCircle, null, Modifier.align(Alignment.Center).size(36.dp), tint = Color.White.copy(alpha = 0.9f))
                    Text(fmtBytes(ci.size), Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(horizontal = 6.dp, vertical = 1.dp),
                        color = Color.White, style = MaterialTheme.typography.labelSmall)
                    androidx.compose.animation.AnimatedVisibility(isSel, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)).border(3.dp, MaterialTheme.colorScheme.primary)) {
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.align(Alignment.TopStart).padding(6.dp), tint = Color.White) }
                    }
                }
            }
        }
    }
    opening?.let { (ci, p) -> AlertDialog({ opening = null }, confirmButton = {}, dismissButton = { TextButton({ opening = null }) { Text("Cancelar") } },
        title = { Text("Abrindo vídeo…") }, text = { Column { Text(ci.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(12.dp))
            LinearProgressIndicator({ p / 100f }, Modifier.fillMaxWidth()) } }) }
    if (confirmDel) AlertDialog({ confirmDel = false }, title = { Text("Apagar ${selected.size} vídeos?") },
        text = { Column { Text("Serão apagados da sua nuvem (Telegram). Não voltam a ser enviados.")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(alsoLocal, { alsoLocal = it }); Text("Apagar também a cópia do cache do Telegram no celular") } } },
        confirmButton = { Button({
            confirmDel = false; val del = items.filter { it.msgId in selected }; selected = emptySet()
            scope.launch {
                var fail = 0
                for (ci in del) try { if (alsoLocal && ci.path.isNotBlank()) Watched.deleteLocal(ctx, ci.path); Repo.deleteFromCloud(ci) } catch (_: Exception) { fail++ }
                msg = if (fail == 0) "${del.size} vídeos apagados." else "$fail não puderam ser apagados."
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Apagar") } },
        dismissButton = { TextButton({ confirmDel = false }) { Text("Cancelar") } })
    msg?.let { AlertDialog({ msg = null }, confirmButton = { TextButton({ msg = null }) { Text("OK") } }, text = { Text(it) }) }
}

@Composable
private fun ShizukuCard(state: ShizState, hasTree: Boolean, onGrant: () -> Unit, onPickTree: () -> Unit) {
    val ctx = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth().padding(4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.tertiary); Spacer(Modifier.width(12.dp))
                Text(if (state == ShizState.NO_PERMISSION) "Falta permitir o Shizuku" else "Ative o Shizuku para pegar tudo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(if (state == ShizState.NO_PERMISSION) "O Shizuku está ligado. Toque em Permitir para o Nuvem ler o cache do Telegram."
                else "O Android esconde o cache do Telegram (pasta Android/data). Sem o Shizuku, o Nuvem só vê os vídeos da pasta pública \"Telegram\".\n\n" +
                    "1. Instale o Shizuku (Play Store).\n2. Ative Opções do desenvolvedor → Depuração por Wi‑Fi.\n3. No Shizuku, toque em \"Iniciar\" (via depuração sem fio) e pareie.\n4. Volte aqui e toque em Permitir.\n\nApós reiniciar o celular, abra o Shizuku e toque em Iniciar de novo.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state == ShizState.NO_PERMISSION) Button(onGrant) { Text("Permitir") }
                else Button({ val i = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"))
                    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Abrir Shizuku") }
                OutlinedButton(onPickTree) { Text(if (hasTree) "Pasta Telegram ✓" else "Usar pasta Telegram") }
            }
        }
    }
}
