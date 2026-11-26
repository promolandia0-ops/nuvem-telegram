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
    val speed: Long = 0,
) {
    val etaSec: Long get() = if (speed > 0 && bytesTotal > bytesDone) (bytesTotal - bytesDone) / speed else 0
    /** "4,2 MB/s • faltam ~12 min" (vazio até ter medida). */
    val speedLine: String get() = if (speed > 0) Backup.fmtSpeed(speed) + (if (etaSec > 0) " • faltam ${Backup.fmtEta(etaSec)}" else "") else ""
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
            l.bytesDone, l.bytesTotal, l.phase, false, l.speed)
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

/** MEGA → Telegram (mesma fila, outra origem): quantos faltam e o que está passando. */
data class MegaUiState(val running: Boolean, val pending: Int, val text: String, val pct: Int)

@Composable
fun rememberMegaState(): MegaUiState {
    val ctx = LocalContext.current
    val wm = remember { WorkManager.getInstance(ctx) }
    val up by wm.getWorkInfosForUniqueWorkFlow(br.geracaoai.nuvem.mega.MegaUpload.WORK).collectAsStateWithLifecycle(emptyList())
    val imp by wm.getWorkInfosForUniqueWorkFlow(br.geracaoai.nuvem.mega.MegaImport.WORK).collectAsStateWithLifecycle(emptyList())
    val later by wm.getWorkInfosForUniqueWorkFlow(br.geracaoai.nuvem.mega.MegaImport.WORK + "_later").collectAsStateWithLifecycle(emptyList())
    val status by br.geracaoai.nuvem.mega.MegaWeb.status.collectAsStateWithLifecycle()
    val runImp = (imp + later).firstOrNull { it.state == WorkInfo.State.RUNNING }
    val runUp = up.any { it.state == WorkInfo.State.RUNNING }
    val pendingUp = up.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED || it.state == WorkInfo.State.RUNNING }
    return when {
        runImp != null -> {
            val d = runImp.progress.getInt("done", 0); val t = runImp.progress.getInt("total", 0)
            MegaUiState(true, (t - d).coerceAtLeast(0) + pendingUp, (if (t > 0) "$d de $t • " else "") + (runImp.progress.getString("name") ?: ""), runImp.progress.getInt("pct", 0))
        }
        runUp || pendingUp > 0 -> MegaUiState(true, pendingUp, status, -1)
        else -> MegaUiState(false, 0, "", -1)
    }
}

/** Faixa de status no topo de todas as abas: fase, %, arquivos, GB e o arquivo atual. */
@Composable
fun BackupBanner(onClick: () -> Unit) {
    val s = rememberBackupState()
    val ctx = LocalContext.current
    val conn by br.geracaoai.nuvem.tg.Td.connection.collectAsStateWithLifecycle()
    val reason = remember(s.queued, conn) { if (s.queued) Backup.queueReason(ctx) else "" }
    val mega = rememberMegaState()
    val queue by BackupQueue.items.collectAsStateWithLifecycle()
    val (icon, tone, title, sub) = when {
        s.scanning -> Quad4(Icons.Rounded.ManageSearch, MaterialTheme.colorScheme.primary, "Procurando arquivos…",
            s.name.ifBlank { "Pode levar alguns minutos no celular inteiro" })
        s.running && conn != "ready" -> Quad4(Icons.Rounded.Sync, Nv.Gold, "Reconectando ao Telegram…",
            "O envio continua sozinho assim que conectar (${s.percent}% feito)")
        s.running -> Quad4(Icons.Rounded.CloudUpload, MaterialTheme.colorScheme.primary,
            "Enviando ${s.percent}% • ${s.done} de ${s.total}" + if (s.speed > 0) " • ${Backup.fmtSpeed(s.speed)}" else "",
            (if (s.etaSec > 0) "Faltam ${Backup.fmtEta(s.etaSec)} • " else "") + (if (s.bytesTotal > 0) "${fmtBytes(s.bytesDone)} de ${fmtBytes(s.bytesTotal)} • " else "") + s.name)
        s.queued -> Quad4(Icons.Rounded.Schedule, Nv.Gold, "Backup na fila", reason)
        mega.running -> Quad4(Icons.Rounded.CloudSync, MaterialTheme.colorScheme.primary, "MEGA → Telegram" + if (mega.pending > 0) " • ${mega.pending} na fila" else "",
            mega.text.ifBlank { "Subindo do MEGA pro Telegram…" })
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
        if (s.running && queue.isNotEmpty()) Text("Na fila depois: ${BackupQueue.describe()}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp, bottom = 6.dp))
        if (s.running && mega.running) Text("MEGA → Telegram também subindo" + if (mega.pending > 0) " (${mega.pending} na fila)" else "", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp, bottom = 6.dp))
        if (s.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
        else if (!s.running && mega.running) { if (mega.pct in 0..100) LinearProgressIndicator({ mega.pct / 100f }, Modifier.fillMaxWidth().height(4.dp)) else LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp)) }
        else if (s.running) LinearProgressIndicator({ s.fraction }, Modifier.fillMaxWidth().height(4.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
    }
}

private data class Quad4(val a: ImageVector, val b: Color, val c: String, val d: String)

/** Central de backup: o que está acontecendo, o que entra no backup e quando. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupCenterScreen(onClose: () -> Unit, onAddFolder: () -> Unit, onAdvanced: () -> Unit, onSpace: () -> Unit = {}) {
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
    var estimateAsk by remember { mutableStateOf(false) }
    var estimate by remember { mutableStateOf<AllEstimate?>(null) }
    LaunchedEffect(Unit) { buckets = withContext(Dispatchers.IO) { try { LocalMedia.buckets(ctx) } catch (_: Exception) { emptyList() } } }
    val freeSpace = rememberFreeSpace()
    val loadingFree = false
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
                            if (s.speedLine.isNotBlank()) Text(s.speedLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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
                        if (!s.running) freeSpace()
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
                item(key = "space") {
                    GlassCard(onClick = onSpace, padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Espaço do celular", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("Descubra o que está ocupando (apps, vídeos escondidos, arquivos grandes) e exclua apps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                }
                item(key = "batt") { SamsungBatteryCard() }
                item(key = "fail") { FailedUploadsCard() }
                item(key = "restq") { RestoreQueueCard() }

                item(key = "tudo") {
                    val allOn = all && FileScan.allPhone
                    GlassCard(padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AllInclusive, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Backup de tudo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("Varre o celular inteiro: fotos, vídeos e arquivos que pesam (1 MB ou mais). Fica de fora: ícones, miniaturas, cache e arquivinhos de app.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            AuroraSwitch(allOn) { on ->
                                if (on) { estimateAsk = true; return@AuroraSwitch }
                                if (on) {
                                    all = true
                                    if (AllFilesAccess.granted()) { Prefs.fileFolders = Prefs.fileFolders + FileScan.rootPath() }
                                    else try { ctx.startActivity(AllFilesAccess.intent(ctx).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                                    fileFolders = Prefs.fileFolders
                                    applyPrefs(); Backup.runNow(ctx, true)
                                } else {
                                    Prefs.fileFolders = Prefs.fileFolders.filterNot { FileScan.isRoot(it) }.toSet(); fileFolders = Prefs.fileFolders
                                    all = false; applyPrefs()
                                }
                            }
                        }
                        if (all && !FileScan.allPhone) Text("Fotos e vídeos já entram. Pra entrar também os arquivos, libere \"Acesso a todos os arquivos\" e ligue de novo.",
                            style = MaterialTheme.typography.labelSmall, color = Nv.Gold, modifier = Modifier.padding(top = 6.dp))
                        Text("Ou escolha na mão: segure um álbum (Álbuns → No celular) ou pastas e arquivos (Arquivos → Celular) e toque em Fazer backup.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                item(key = "fila") {
                    val q by BackupQueue.items.collectAsStateWithLifecycle()
                    val mega = rememberMegaState()
                    var showQ by remember { mutableStateOf(false) }
                    if (showQ) QueueSheet { showQ = false }
                    if (q.isNotEmpty() || mega.running) GlassCard(onClick = { if (q.isNotEmpty()) showQ = true }, padding = 14.dp) {
                        Text("Fila do backup", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (q.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PlaylistAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text("Escolhidos: ${BackupQueue.describe()}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton({ showQ = true }) { Text("Ver") }
                        }
                        if (mega.running) Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CloudSync, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text("MEGA → Telegram" + (if (mega.pending > 0) ": ${mega.pending} na fila" else "") + (if (mega.text.isNotBlank()) " • ${mega.text}" else ""),
                                style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
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
    if (estimateAsk) {
        LaunchedEffect(Unit) { estimate = null; estimate = withContext(Dispatchers.IO) { estimateAll(ctx) } }
        val e = estimate
        AlertDialog({ estimateAsk = false },
            icon = { Icon(Icons.Rounded.AllInclusive, null) },
            title = { Text("Ligar o backup de tudo?") },
            text = {
                if (e == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)); Text("Medindo o que tem no celular…")
                } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Fotos e vídeos: ${e.mediaN} • ${fmtBytes(e.mediaBytes)}")
                    Text(if (e.filesKnown) "Arquivos que pesam: ${e.filesN} • ${fmtBytes(e.filesBytes)}" else "Arquivos: precisa liberar \"Acesso a todos os arquivos\" (vou abrir a tela)")
                    Text("Já na nuvem: ${e.cloudN} • ${fmtBytes(e.cloudBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    Text("Vai mandar ~${fmtBytes((e.mediaBytes + e.filesBytes - e.cloudBytes).coerceAtLeast(0))}", fontWeight = FontWeight.SemiBold)
                    Text("Fica de fora: ícones, miniaturas, cache, logs e arquivos menores que 1 MB.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(enabled = e != null, onClick = {
                estimateAsk = false
                all = true
                if (AllFilesAccess.granted()) Prefs.fileFolders = Prefs.fileFolders + FileScan.rootPath()
                else try { ctx.startActivity(AllFilesAccess.intent(ctx).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                fileFolders = Prefs.fileFolders
                applyPrefs(); Backup.runNow(ctx, true)
            }) { Text("Ligar") } },
            dismissButton = { TextButton({ estimateAsk = false }) { Text("Cancelar") } })
    }
    removeFolder?.let { path ->
        AlertDialog({ removeFolder = null },
            title = { Text("Tirar \"${path.substringAfterLast('/')}\" do backup?") },
            text = { Text("Arquivos novos dessa pasta não sobem mais. O que já foi enviado continua na nuvem.") },
            confirmButton = { Button({ Prefs.fileFolders = Prefs.fileFolders - path; fileFolders = Prefs.fileFolders; removeFolder = null }) { Text("Tirar") } },
            dismissButton = { TextButton({ removeFolder = null }) { Text("Cancelar") } })
    }
}

data class AllEstimate(val mediaN: Int, val mediaBytes: Long, val filesN: Int, val filesBytes: Long, val filesKnown: Boolean, val cloudN: Int, val cloudBytes: Long)

/** Mede o "backup de tudo": fotos/vídeos (sem ícones), arquivos que pesam e o que já está na nuvem. */
fun estimateAll(c: android.content.Context): AllEstimate {
    val media = try { MediaScan.allAnywhere(c) } catch (_: Exception) { emptyList() }
    val known = AllFilesAccess.granted()
    val root = FileScan.rootPath()
    val files = if (!known) emptyList() else {
        val dirs = (java.io.File(root).listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") && !it.name.equals("Android", true) }
        dirs.flatMap { try { FileScan.walk(FileScan.SMART + it.absolutePath) } catch (_: Exception) { emptyList() } } +
            (try { FileScan.walk(FileScan.FLAT + root) } catch (_: Exception) { emptyList() })
    }
    val cloud = try { Db.allCloudMany(Prefs.allKnownChats()).map { it.name to it.size }.toHashSet() } catch (_: Exception) { hashSetOf() }
    val inCloud = (media + files).filter { (it.name to it.size) in cloud }
    return AllEstimate(media.size, media.sumOf { it.size }, files.size, files.sumOf { it.size }, known, inCloud.size, inCloud.sumOf { it.size })
}

/** Lista do que está na fila (escolhido na mão), com X pra tirar cada um. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(onClose: () -> Unit) {
    val q by BackupQueue.items.collectAsStateWithLifecycle()
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fila do backup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (q.isNotEmpty()) TextButton({ BackupQueue.clear() }) { Text("Tirar tudo") }
            }
            Text("Sobem na próxima rodada do backup, junto com o automático.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (q.isEmpty()) Text("Nada na fila.", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(q.sorted(), key = { it }) { e ->
                    val (icon, title, sub) = when {
                        e.startsWith(DeepScan.PREFIX) -> Triple(Icons.Rounded.Lock, DeepScan.groups.value?.firstOrNull { it.key == e }?.label ?: e.removePrefix(DeepScan.PREFIX), "Escondido/protegido (Shizuku)")
                        e.startsWith("bucket:") -> Triple(Icons.Rounded.PhotoAlbum, folderLabel(e.removePrefix("bucket:")), "Álbum do celular")
                        e.startsWith("dir:") -> Triple(Icons.Rounded.Folder, e.removePrefix("dir:").substringAfterLast('/'), FileScan.relOf(e.removePrefix("dir:")).ifBlank { "Pasta" })
                        else -> Triple(Icons.Rounded.InsertDriveFile, e.removePrefix("file:").substringAfterLast('/'),
                            fmtBytes(java.io.File(e.removePrefix("file:")).length()) + " • " + FileScan.relOf(e.removePrefix("file:").substringBeforeLast('/')))
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = Nv.Gold)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton({ BackupQueue.remove(listOf(e)) }) { Icon(Icons.Rounded.Close, "Tirar da fila") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Cabeçalho das abas: título grande + ações (backup e ajustes). */
@Composable
fun TabHeaderActions(onBackup: () -> Unit, onSettings: () -> Unit) {
    BackupStatusIcon(onBackup)
    IconButton(onSettings) { Icon(Icons.Rounded.Settings, "Ajustes") }
}
