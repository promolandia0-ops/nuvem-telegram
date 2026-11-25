package br.geracaoai.nuvem.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.work.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/** Estado do backup lido do WorkManager, para a barra de status e a central. */
data class BackupUiState(
    val running: Boolean,
    val paused: Boolean,
    val done: Int,
    val total: Int,
    val name: String,
    val pct: Int,
    val error: String,
) {
    val fraction get() = if (total > 0) (done / total.toFloat()).coerceIn(0f, 1f) else 0f
}

@Composable
fun rememberBackupState(): BackupUiState {
    val ctx = LocalContext.current
    val wm = remember { WorkManager.getInstance(ctx) }
    val now by wm.getWorkInfosForUniqueWorkFlow(Backup.NOW).collectAsStateWithLifecycle(emptyList())
    val per by wm.getWorkInfosForUniqueWorkFlow(Backup.PERIODIC).collectAsStateWithLifecycle(emptyList())
    val cont by wm.getWorkInfosForUniqueWorkFlow(Backup.CONTINUE).collectAsStateWithLifecycle(emptyList())
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val paused = remember(tick, now, per, cont) { Prefs2.backupPaused }
    val error = remember(tick, now, per, cont) { Prefs.lastError }
    val w = if (paused) null else (now + per + cont).firstOrNull { it.state == WorkInfo.State.RUNNING }
    val p = w?.progress
    return BackupUiState(
        running = w != null,
        paused = paused,
        done = p?.getInt("done", 0) ?: 0,
        total = p?.getInt("total", 0) ?: 0,
        name = p?.getString("name") ?: "",
        pct = p?.getInt("pct", 0) ?: 0,
        error = if (isCancelMessage(error)) "" else error,
    )
}

private fun backupConfigured() = Prefs.backupAll || Prefs.folders.isNotEmpty() || Prefs.secretFolders.isNotEmpty() || Prefs.fileFolders.isNotEmpty()

/** Ícone de nuvem no topo: gira enquanto envia. Toque abre a central de backup. */
@Composable
fun BackupStatusIcon(onClick: () -> Unit) {
    val s = rememberBackupState()
    val spin by rememberInfiniteTransition(label = "bk").animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "r")
    IconButton(onClick) {
        when {
            s.running -> Icon(Icons.Rounded.Sync, "Backup em andamento", Modifier.rotate(-spin), tint = MaterialTheme.colorScheme.primary)
            s.paused -> Icon(Icons.Rounded.CloudOff, "Backup pausado", tint = Nv.Gold)
            s.error.isNotBlank() -> Icon(Icons.Rounded.ErrorOutline, "Backup com aviso", tint = Nv.Rose)
            backupConfigured() -> Icon(Icons.Rounded.CloudDone, "Backup em dia", tint = MaterialTheme.colorScheme.primary)
            else -> Icon(Icons.Rounded.CloudUpload, "Ligar backup")
        }
    }
}

/** Faixa no topo de Fotos e Vídeos: diz em uma linha o que o backup está fazendo. */
@Composable
fun BackupBanner(onClick: () -> Unit) {
    val s = rememberBackupState()
    val (icon, tone, title, sub) = when {
        s.running -> Quad4(Icons.Rounded.CloudUpload, MaterialTheme.colorScheme.primary,
            if (s.total > 0) "Enviando ${s.done} de ${s.total}" else "Preparando o backup…",
            s.name.ifBlank { "Fazendo backup no Telegram" })
        s.paused -> Quad4(Icons.Rounded.PauseCircle, Nv.Gold, "Backup pausado", "Toque para retomar")
        !backupConfigured() -> Quad4(Icons.Rounded.CloudUpload, MaterialTheme.colorScheme.primary, "Backup desligado", "Toque para escolher o que salvar")
        s.error.isNotBlank() -> Quad4(Icons.Rounded.ErrorOutline, Nv.Rose, "Backup precisa de atenção", traduz(s.error))
        else -> Quad4(Icons.Rounded.CloudDone, MaterialTheme.colorScheme.primary, "Backup em dia",
            if (Prefs.lastBackup > 0) "Último: " + SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(Prefs.lastBackup)) else "Novas fotos sobem sozinhas")
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tone)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.running) LinearProgressIndicator({ s.fraction }, Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent)
    }
}

private data class Quad4(val a: ImageVector, val b: Color, val c: String, val d: String)

/** Central de backup: o que está acontecendo, o que entra no backup e quando. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupCenterScreen(onClose: () -> Unit, onAddFolder: () -> Unit, onAdvanced: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = rememberBackupState()
    var all by remember { mutableStateOf(Prefs.backupAll) }
    var mediaSel by remember { mutableStateOf(Prefs.folders) }
    var fileFolders by remember { mutableStateOf(Prefs.fileFolders) }
    var auto by remember { mutableStateOf(Prefs.autoBackup) }
    var wifi by remember { mutableStateOf(Prefs.wifiOnly) }
    var charging by remember { mutableStateOf(Prefs.chargingOnly) }
    var instant by remember { mutableStateOf(Prefs.instantBackup) }
    var buckets by remember { mutableStateOf<List<Bucket>>(emptyList()) }
    var removeFolder by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { buckets = withContext(Dispatchers.IO) { try { LocalMedia.buckets(ctx) } catch (_: Exception) { emptyList() } } }
    fun applyPrefs() {
        Prefs.backupAll = all; Prefs.folders = mediaSel; Prefs.autoBackup = auto; Prefs.wifiOnly = wifi
        Prefs.chargingOnly = charging; Prefs.instantBackup = instant
        scope.launch(Dispatchers.IO) { Backup.schedule(ctx) }
    }
    val configured = all || mediaSel.isNotEmpty() || fileFolders.isNotEmpty() || Prefs.secretFolders.isNotEmpty()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
                Text("Backup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "status") {
                    GlassCard(padding = 16.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(0.14f)), contentAlignment = Alignment.Center) {
                                Icon(when { s.running -> Icons.Rounded.CloudUpload; s.paused -> Icons.Rounded.PauseCircle; else -> Icons.Rounded.CloudDone }, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(when {
                                    s.running && s.total > 0 -> "Enviando ${s.done} de ${s.total}"
                                    s.running -> "Preparando…"
                                    s.paused -> "Pausado"
                                    !configured -> "Desligado"
                                    else -> "Em dia"
                                }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                val sub = when {
                                    s.running -> s.name
                                    s.error.isNotBlank() -> traduz(s.error)
                                    Prefs.lastBackup > 0 -> "Último backup: " + SimpleDateFormat("dd/MM 'às' HH:mm", Locale("pt", "BR")).format(Date(Prefs.lastBackup))
                                    else -> "Escolha abaixo o que vai pra nuvem."
                                }
                                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = if (s.error.isNotBlank() && !s.running) Nv.Rose else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (s.running) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator({ s.fraction }, Modifier.fillMaxWidth().clip(CircleShape))
                            if (s.pct in 1..99) {
                                Spacer(Modifier.height(6.dp))
                                Text("Arquivo atual: ${s.pct}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            when {
                                s.running -> OutlinedButton({ scope.launch(Dispatchers.IO) { Backup.stop(ctx) } }, Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.Pause, null); Spacer(Modifier.width(6.dp)); Text("Pausar")
                                }
                                s.paused -> Button({ applyPrefs(); scope.launch(Dispatchers.IO) { Backup.resume(ctx) } }, Modifier.weight(1f), enabled = configured) {
                                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Retomar")
                                }
                                else -> Button({ applyPrefs(); Backup.runNow(ctx, true) }, Modifier.weight(1f), enabled = configured) {
                                    Icon(Icons.Rounded.CloudUpload, null); Spacer(Modifier.width(6.dp)); Text("Fazer backup agora")
                                }
                            }
                        }
                    }
                }
                item(key = "fail") { FailedUploadsCard() }
                item(key = "restq") { RestoreQueueCard() }

                item(key = "h_media") { SectionTitle("Fotos e vídeos") }
                item(key = "media") {
                    GlassCard(padding = 8.dp) {
                        SettingSwitch("Todas as fotos e vídeos", "Câmera, WhatsApp, Telegram, Downloads… tudo do celular, sem limite.", all) { all = it; applyPrefs() }
                        if (!all) {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Text("Só estas pastas:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                            if (buckets.isEmpty()) Text("Procurando pastas…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
                            buckets.forEach { b ->
                                val on = b.name in mediaSel
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                    mediaSel = if (on) mediaSel - b.name else mediaSel + b.name; applyPrefs()
                                }.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(on, { mediaSel = if (on) mediaSel - b.name else mediaSel + b.name; applyPrefs() })
                                    Text(folderLabel(b.name), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${b.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                item(key = "h_files") { SectionTitle("Pastas inteiras (qualquer arquivo)") }
                item(key = "files") {
                    GlassCard(padding = 8.dp) {
                        if (fileFolders.isEmpty()) Text("Nenhuma pasta ainda. Em Arquivos, toque na nuvem ao lado de uma pasta para salvar ela inteira: documentos, PDFs, ZIP, áudio, tudo.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                        fileFolders.sorted().forEach { path ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Folder, null, tint = Nv.Gold)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(FileScan.relOf(path).ifBlank { path }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton({ removeFolder = path }) { Icon(Icons.Rounded.Close, "Tirar do backup") }
                            }
                        }
                        TextButton(onAddFolder, Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("Adicionar pasta")
                        }
                    }
                }

                item(key = "h_when") { SectionTitle("Quando") }
                item(key = "when") {
                    GlassCard(padding = 8.dp) {
                        SettingSwitch("Automático", "Sobe sozinho de tempos em tempos, sem abrir o app.", auto) { auto = it; applyPrefs() }
                        SettingSwitch("Na hora", "Foto ou vídeo novo sobe logo que aparece.", instant) { instant = it; applyPrefs() }
                        SettingSwitch("Só no Wi-Fi", "Não gasta seus dados móveis.", wifi) { wifi = it; applyPrefs() }
                        SettingSwitch("Só carregando", "Espera o celular estar na tomada.", charging) { charging = it; applyPrefs() }
                    }
                }
                item(key = "adv") {
                    GlassCard(onClick = onAdvanced, padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Tune, null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Mais opções", style = MaterialTheme.typography.titleSmall)
                                Text("Liberar espaço, duplicadas, restaurar álbum, pastas secretas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                }
            }
        }
    }
    removeFolder?.let { path ->
        AlertDialog({ removeFolder = null },
            title = { Text("Tirar \"${path.substringAfterLast('/')}\" do backup?") },
            text = { Text("Arquivos novos dessa pasta não sobem mais. O que já foi enviado continua na nuvem.") },
            confirmButton = { Button({ Prefs.fileFolders = Prefs.fileFolders - path; fileFolders = Prefs.fileFolders; removeFolder = null }) { Text("Tirar") } },
            dismissButton = { TextButton({ removeFolder = null }) { Text("Cancelar") } })
    }
}

/** Cabeçalho das abas: título grande + ações (backup e ajustes). */
@Composable
fun TabHeaderActions(onBackup: () -> Unit, onSettings: () -> Unit) {
    BackupStatusIcon(onBackup)
    IconButton(onSettings) { Icon(Icons.Rounded.Settings, "Ajustes") }
}
