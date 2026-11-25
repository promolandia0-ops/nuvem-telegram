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
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val phase: String = "",
    val queued: Boolean = false,
) {
    /** Progresso geral: por GB quando dá (mais honesto com vídeo grande), senão por arquivo. */
    val fraction get() = when {
        bytesTotal > 0 -> (bytesDone / bytesTotal.toDouble()).toFloat().coerceIn(0f, 1f)
        total > 0 -> (done / total.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }
    val percent get() = (fraction * 100).toInt()
    val scanning get() = running && (phase == "scan" || phase == "prep" || total == 0)
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
    val lv by Backup.live.collectAsStateWithLifecycle()
    // Ao vivo (mesmo processo do backup): número certo na hora, igual à notificação.
    lv?.let { l ->
        if (!paused) return BackupUiState(true, false, l.done, l.total, l.name, l.filePct, if (isCancelMessage(error)) "" else error,
            l.bytesDone, l.bytesTotal, l.phase, false)
    }
    val w = if (paused) null else (now + per + cont).firstOrNull { it.state == WorkInfo.State.RUNNING }
    val queued = !paused && w == null && (now + cont).any { it.state == WorkInfo.State.ENQUEUED }
    val p = w?.progress
    return BackupUiState(
        running = w != null,
        paused = paused,
        done = p?.getInt("done", 0) ?: 0,
        total = p?.getInt("total", 0) ?: 0,
        name = p?.getString("name") ?: "",
        pct = p?.getInt("pct", 0) ?: 0,
        error = if (isCancelMessage(error)) "" else error,
        bytesDone = p?.getLong("bytesDone", 0L) ?: 0L,
        bytesTotal = p?.getLong("bytesTotal", 0L) ?: 0L,
        phase = p?.getString("phase") ?: "",
        queued = queued,
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

private val hhmm = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
private val ddhhmm = SimpleDateFormat("dd/MM 'às' HH:mm", Locale("pt", "BR"))

/** Comprovante da última rodada: "Concluído às 16:40 • …". */
fun lastRunLine(): String {
    val at = Backup.lastRunAt(); val t = Backup.lastRunText()
    if (at <= 0 || t.isBlank()) return ""
    val same = System.currentTimeMillis() - at < 20 * 3600_000L
    return "Concluído ${if (same) "às " + hhmm.format(Date(at)) else "em " + ddhhmm.format(Date(at))} • $t"
}

/** Faixa de status no topo de todas as abas: fase, %, arquivos, GB e o arquivo atual. */
@Composable
fun BackupBanner(onClick: () -> Unit) {
    val s = rememberBackupState()
    val ctx = LocalContext.current
    val conn by br.geracaoai.nuvem.tg.Td.connection.collectAsStateWithLifecycle()
    val reason = remember(s.queued, conn) { if (s.queued) Backup.queueReason(ctx) else "" }
    val (icon, tone, title, sub) = when {
        s.scanning -> Quad4(Icons.Rounded.ManageSearch, MaterialTheme.colorScheme.primary, "Procurando arquivos…",
            s.name.ifBlank { "Pode levar alguns minutos no celular inteiro" })
        s.running && conn != "ready" -> Quad4(Icons.Rounded.Sync, Nv.Gold, "Reconectando ao Telegram…",
            "O envio continua sozinho assim que conectar (${s.percent}% feito)")
        s.running -> Quad4(Icons.Rounded.CloudUpload, MaterialTheme.colorScheme.primary,
            "Enviando ${s.percent}% • ${s.done} de ${s.total}",
            (if (s.bytesTotal > 0) "${fmtBytes(s.bytesDone)} de ${fmtBytes(s.bytesTotal)} • " else "") + s.name)
        s.queued -> Quad4(Icons.Rounded.Schedule, Nv.Gold, "Backup na fila", reason)
        s.paused -> Quad4(Icons.Rounded.PauseCircle, Nv.Gold, "Backup pausado", "Toque para retomar")
        !backupConfigured() -> Quad4(Icons.Rounded.CloudUpload, MaterialTheme.colorScheme.primary, "Backup desligado", "Toque para escolher o que salvar")
        s.error.isNotBlank() -> Quad4(Icons.Rounded.ErrorOutline, Nv.Rose, "Backup precisa de atenção", traduz(s.error))
        else -> Quad4(Icons.Rounded.CloudDone, MaterialTheme.colorScheme.primary, "Backup em dia",
            lastRunLine().ifBlank { "Novos arquivos sobem sozinhos" })
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
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
        else if (s.running) LinearProgressIndicator({ s.fraction }, Modifier.fillMaxWidth().height(4.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
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
    var cleanup by remember { mutableStateOf<List<LocalItem>?>(null) }
    var loadingFree by remember { mutableStateOf(false) }
    var freeEmpty by remember { mutableStateOf(false) }
    CleanupFlow(cleanup) { _ -> cleanup = null }
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
                                    s.scanning -> "Procurando arquivos…"
                                    s.running -> "Enviando ${s.percent}%"
                                    s.queued -> "Na fila"
                                    s.paused -> "Pausado"
                                    !configured -> "Desligado"
                                    else -> "Em dia"
                                }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                val sub = when {
                                    s.running -> s.name
                                    s.queued -> Backup.queueReason(ctx)
                                    s.error.isNotBlank() -> traduz(s.error)
                                    lastRunLine().isNotBlank() -> lastRunLine()
                                    Prefs.lastBackup > 0 -> "Último backup: " + SimpleDateFormat("dd/MM 'às' HH:mm", Locale("pt", "BR")).format(Date(Prefs.lastBackup))
                                    else -> "Escolha abaixo o que vai pra nuvem."
                                }
                                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = if (s.error.isNotBlank() && !s.running) Nv.Rose else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (s.scanning) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                        } else if (s.running) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator({ s.fraction }, Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Text("${s.done} de ${s.total} arquivos", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                if (s.bytesTotal > 0) Text("${fmtBytes(s.bytesDone)} de ${fmtBytes(s.bytesTotal)}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (s.pct in 1..99) {
                                Spacer(Modifier.height(4.dp))
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
                                else -> Button({
                                    applyPrefs(); Backup.runNow(ctx, true)
                                    // Primeira vez: pede pra não otimizar a bateria (senão o Android mata o backup com a tela desligada).
                                    if (!br.geracaoai.nuvem.work.BatteryGuide.ignoring(ctx) && !Prefs.raw.getBoolean("batt_asked", false)) {
                                        Prefs.raw.edit().putBoolean("batt_asked", true).apply()
                                        br.geracaoai.nuvem.work.BatteryGuide.openBest(ctx)
                                    }
                                }, Modifier.weight(1f), enabled = configured) {
                                    Icon(Icons.Rounded.CloudUpload, null); Spacer(Modifier.width(6.dp)); Text("Fazer backup agora")
                                }
                            }
                        }
                    }
                }
                item(key = "free") {
                    GlassCard(onClick = {
                        if (loadingFree || s.running) return@GlassCard
                        loadingFree = true
                        scope.launch {
                            val list = withContext(Dispatchers.IO) { try { Repo.freeableQuick(ctx).items } catch (_: Exception) { emptyList() } }
                            loadingFree = false
                            if (list.isEmpty()) freeEmpty = true else cleanup = list
                        }
                    }, padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CleaningServices, null, tint = Nv.Gold)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Liberar espaço do celular", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(when {
                                    s.running -> "Espere o backup terminar."
                                    loadingFree -> "Procurando o que já está na nuvem…"
                                    else -> "Mostra quantos GB saem, confere cada arquivo na nuvem e mede o espaço livre antes e depois."
                                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (loadingFree) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                }
                item(key = "batt") { SamsungBatteryCard() }
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
                                    Text(if (FileScan.isRoot(path)) "Todos os arquivos do celular" else path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (FileScan.isRoot(path)) "Todas as pastas, menos Android e ocultas" else FileScan.relOf(path).ifBlank { path }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    if (freeEmpty) AlertDialog({ freeEmpty = false }, confirmButton = { TextButton({ freeEmpty = false }) { Text("OK") } },
        title = { Text("Nada pra liberar agora") },
        text = { Text("Não achei no celular nenhum arquivo que já esteja salvo na nuvem. Faça o backup primeiro; depois volte aqui.") })
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
