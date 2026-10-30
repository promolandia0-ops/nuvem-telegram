package br.geracaoai.nuvem.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.work.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AlbumsScreen(onOpen: (List<CloudItem>, Int) -> Unit) {
    val ver by Db.version.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var albums by remember { mutableStateOf<List<Pair<String, List<CloudItem>>>>(emptyList()) }
    LaunchedEffect(ver) { albums = withContext(Dispatchers.IO) { Db.allCloud(Prefs.chatId).groupBy { it.folder }.toList().sortedByDescending { it.second.size } } }
    BackHandler(open != null) { open = null }
    AnimatedContent(open, transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut() }, label = "alb") { o ->
        if (o == null) LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Álbuns por pasta", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(4.dp)) }
            items(albums, key = { it.first }) { (name, list) ->
                Column(Modifier.clickable { open = name }) {
                    Thumb(list.first(), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
                    Text(name.ifBlank { "Sem pasta" }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text("${list.size} itens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else Column {
            Row(verticalAlignment = Alignment.CenterVertically) { IconButton({ open = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }; Text(o, style = MaterialTheme.typography.titleLarge) }
            GalleryScreen(o, onOpen)
        }
    }
}

@Composable
fun BackupScreen() {
    val ctx = LocalContext.current
    val wm = WorkManager.getInstance(ctx)
    val now by wm.getWorkInfosForUniqueWorkFlow(Backup.NOW).collectAsStateWithLifecycle(emptyList())
    val per by wm.getWorkInfosForUniqueWorkFlow(Backup.PERIODIC).collectAsStateWithLifecycle(emptyList())
    var paused by remember { mutableStateOf(Prefs2.backupPaused) }
    // Ao pausar, a UI para na hora (não espera o WorkManager confirmar o cancelamento).
    val running = if (paused) null else (now + per).firstOrNull { it.state == WorkInfo.State.RUNNING }
    var buckets by remember { mutableStateOf<List<Bucket>>(emptyList()) }
    var sel by remember { mutableStateOf(Prefs.folders) }
    var auto by remember { mutableStateOf(Prefs.autoBackup) }
    var wifi by remember { mutableStateOf(Prefs.wifiOnly) }
    var charging by remember { mutableStateOf(Prefs.chargingOnly) }
    LaunchedEffect(Unit) { buckets = withContext(Dispatchers.IO) { try { LocalMedia.buckets(ctx) } catch (_: Exception) { emptyList() } } }
    fun save() { Prefs.folders = sel; Prefs.autoBackup = auto; Prefs.wifiOnly = wifi; Prefs.chargingOnly = charging; Backup.schedule(ctx) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (running != null) Icons.Rounded.CloudSync else Icons.Rounded.CloudDone, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(if (running != null) "Fazendo backup…" else if (paused) "Backup pausado" else "Backup", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    AnimatedContent(running, label = "prog") { r ->
                        if (r != null) {
                            val d = r.progress.getInt("done", 0); val t = r.progress.getInt("total", 0); val p = r.progress.getInt("pct", 0)
                            Column {
                                Text("$d de $t • ${r.progress.getString("name") ?: "verificando…"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(6.dp)); LinearProgressIndicator({ if (t == 0) 0f else d / t.toFloat() }, Modifier.fillMaxWidth())
                                Spacer(Modifier.height(4.dp)); LinearProgressIndicator({ p / 100f }, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiary)
                            }
                        } else Text(buildString {
                            if (paused) append("Parado por você. Nada será enviado até tocar em Retomar.\n")
                            append(if (Prefs.lastBackup > 0) "Último backup: " + SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(Prefs.lastBackup)) else "Nenhum backup ainda")
                            if (Prefs.lastError.isNotBlank()) append("\nÚltimo erro: ${traduz(Prefs.lastError)}")
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (paused) Button({ paused = false; save(); Backup.resume(ctx) }, enabled = sel.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Retomar") }
                        else Button({ save(); Backup.runNow(ctx, true) }, enabled = running == null && sel.isNotEmpty()) { Icon(Icons.Rounded.CloudUpload, null); Spacer(Modifier.width(6.dp)); Text("Fazer backup agora") }
                        if (running != null) OutlinedButton({ paused = true; Backup.stop(ctx) }) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text("Parar") }
                    }
                }
            }
        }
        item { FreeUpCard() }
        item { Text("Opções", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        item { SwitchRow("Backup automático", "A cada hora, em segundo plano", auto) { auto = it; save() } }
        item { SwitchRow("Somente no Wi-Fi", "Não usa dados móveis", wifi) { wifi = it; save() } }
        item { SwitchRow("Somente carregando", "Economiza bateria", charging) { charging = it; save() } }
        item { Text("Pastas para backup", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (buckets.isEmpty()) item { Text("Permita o acesso a fotos e vídeos para listar as pastas.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(buckets, key = { it.name }) { b ->
            ListItem(headlineContent = { Text(b.name.ifBlank { "(sem nome)" }) }, supportingContent = { Text("${b.count} itens") },
                leadingContent = { Icon(if (b.name.equals("Camera", true)) Icons.Rounded.PhotoCamera else Icons.Rounded.Folder, null) },
                trailingContent = { Checkbox(b.name in sel, { c -> sel = if (c) sel + b.name else sel - b.name; save() }) },
                modifier = Modifier.clickable { sel = if (b.name in sel) sel - b.name else sel + b.name; save() })
        }
    }
}

@Composable
fun SwitchRow(title: String, sub: String, v: Boolean, on: (Boolean) -> Unit) =
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(sub) }, trailingContent = { Switch(v, on) }, modifier = Modifier.clickable { on(!v) })

@Composable
fun SettingsScreen(onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val theme by Prefs.theme.collectAsStateWithLifecycle()
    var confirmOut by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    val premium by Td.isPremium.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item { Text("Ajustes", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp)) }
        item { ListItem(headlineContent = { Text("Destino") }, supportingContent = { Text(Prefs.chatTitle.ifBlank { "—" } + if (premium) " • Premium (até 4 GB por arquivo)" else " • até 2 GB por arquivo") }, leadingContent = { Icon(Icons.Rounded.Cloud, null) }) }
        item {
            ListItem(headlineContent = { Text("Tema") }, leadingContent = { Icon(Icons.Rounded.Palette, null) }, supportingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Sistema", "Claro", "Escuro").forEachIndexed { i, l -> FilterChip(theme == i, { Prefs.setTheme(i) }, { Text(l) }) } }
            })
        }
        item { ListItem(headlineContent = { Text("Reindexar nuvem") }, supportingContent = { Text(if (syncing) "Sincronizando…" else "Relê todo o canal (útil se apagou algo pelo Telegram)") },
            leadingContent = { Icon(Icons.Rounded.Sync, null) }, modifier = Modifier.clickable(enabled = !syncing) { syncing = true; scope.launch { try { Repo.syncIndex(full = true) } catch (_: Exception) {}; syncing = false } }) }
        item { ListItem(headlineContent = { Text("Trocar destino") }, supportingContent = { Text("Escolher outro canal ou Mensagens Salvas") }, leadingContent = { Icon(Icons.Rounded.SwapHoriz, null) },
            modifier = Modifier.clickable { Prefs.chatId = 0; onLogout() }) }
        item { ListItem(headlineContent = { Text("Sair da conta") }, leadingContent = { Icon(Icons.AutoMirrored.Rounded.Logout, null) }, modifier = Modifier.clickable { confirmOut = true }) }
        item { Text("Nuvem 1.1 • seus arquivos ficam na sua conta do Telegram. Nada passa por servidores de terceiros.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
    }
    if (confirmOut) AlertDialog({ confirmOut = false }, title = { Text("Sair da conta?") }, text = { Text("Seus arquivos continuam no Telegram. O backup automático para até você entrar de novo.") },
        confirmButton = { Button({ confirmOut = false; scope.launch { try { Td.send(TdApi.LogOut()) } catch (_: Exception) {}; Prefs.chatId = 0; onLogout() } }) { Text("Sair") } },
        dismissButton = { TextButton({ confirmOut = false }) { Text("Cancelar") } })
}
