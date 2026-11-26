package br.geracaoai.nuvem.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.shizuku.ShizState
import br.geracaoai.nuvem.shizuku.Shiz
import br.geracaoai.nuvem.work.Backup
import br.geracaoai.nuvem.work.Uninstall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Atualiza um contador toda vez que o app volta pra frente (ex.: depois de liberar uma permissão). */
@Composable
private fun resumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val life = LocalLifecycleOwner.current
    DisposableEffect(life) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        life.lifecycle.addObserver(obs); onDispose { life.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
private fun ScreenTop(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

private val CatApps = Color(0xFF7C9CFF)
private val CatImg = Color(0xFF3EE0C9)
private val CatVid = Color(0xFFB794F6)
private val CatAud = Color(0xFFFF8A65)
private val CatOther = Color(0xFFF5C16C)
private val CatSys = Color(0xFF90A4AE)

/** O que está ocupando o celular: categorias, apps e maiores arquivos. */
@Composable
fun SpaceScreen(onBack: () -> Unit, onApps: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = resumeTick()
    var sum by remember { mutableStateOf<Space.Summary?>(null) }
    var big by remember { mutableStateOf<List<Space.Big>?>(null) }
    var sel by remember { mutableStateOf(setOf<String>()) }
    var cleanup by remember { mutableStateOf<List<LocalItem>?>(null) }
    var onCloud by remember { mutableStateOf<Set<Pair<String, Long>>>(emptySet()) }
    LaunchedEffect(tick) {
        sum = withContext(Dispatchers.IO) { try { Space.summary(ctx) } catch (_: Exception) { null } }
        onCloud = withContext(Dispatchers.IO) { try { Db.allCloudMany(Prefs.allKnownChats()).map { it.name to it.size }.toHashSet() } catch (_: Exception) { emptySet() } }
        big = withContext(Dispatchers.IO) { try { Space.biggest(ctx) } catch (_: Exception) { emptyList() } }
    }
    CleanupFlow(cleanup) { _ -> cleanup = null; sel = emptySet(); scope.launch { big = withContext(Dispatchers.IO) { Space.biggest(ctx) }; sum = withContext(Dispatchers.IO) { Space.summary(ctx) } } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ScreenTop("Espaço do celular", onBack)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "sum") {
                    val s = sum
                    GlassCard(padding = 16.dp) {
                        if (s == null) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("Medindo…") }
                        else {
                            val pct = if (s.total > 0) (s.used * 100 / s.total).toInt() else 0
                            Text("$pct% usado", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("${fmtBytes(s.used)} de ${fmtBytes(s.total)} • livre ${fmtBytes(s.free)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            val parts = listOf(s.apps to CatApps, s.images to CatImg, s.videos to CatVid, s.audio to CatAud, s.other to CatOther, s.system to CatSys).filter { it.first > 0 }
                            Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                parts.forEach { (b, c) -> Box(Modifier.weight((b.toFloat() / s.total.coerceAtLeast(1)).coerceAtLeast(0.005f)).fillMaxHeight().background(c)) }
                                val freeW = s.free.toFloat() / s.total.coerceAtLeast(1)
                                if (freeW > 0.005f) Spacer(Modifier.weight(freeW))
                            }
                            Spacer(Modifier.height(10.dp))
                            CatRow(Icons.Rounded.Apps, CatApps, "Aplicativos (com os dados deles)", if (s.appsKnown) fmtBytes(s.apps) else "toque pra ver", onApps)
                            CatRow(Icons.Rounded.Image, CatImg, "Fotos", fmtBytes(s.images))
                            CatRow(Icons.Rounded.Movie, CatVid, "Vídeos", fmtBytes(s.videos))
                            CatRow(Icons.Rounded.AudioFile, CatAud, "Áudio", fmtBytes(s.audio))
                            CatRow(Icons.Rounded.Description, CatOther, "Documentos e outros arquivos", fmtBytes(s.other))
                            CatRow(Icons.Rounded.PhoneAndroid, CatSys, if (s.appsKnown) "Sistema e o resto" else "Sistema, apps e o resto", fmtBytes(s.system))
                            if (!s.appsKnown) {
                                Spacer(Modifier.height(8.dp))
                                Text("Pra saber quanto cada app ocupa (com vídeos e arquivos que eles guardam), libere \"Acesso ao uso\" pro Nuvem.",
                                    style = MaterialTheme.typography.bodySmall, color = Nv.Gold)
                                TextButton({ try { ctx.startActivity(Space.usageIntent(ctx)) } catch (_: Exception) {} }) { Text("Liberar Acesso ao uso") }
                            }
                        }
                    }
                }
                item(key = "apps") {
                    GlassCard(onClick = onApps, padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.DeleteSweep, null, tint = Nv.Rose)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Excluir aplicativos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("Veja os apps que mais ocupam e exclua vários de uma vez.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                }
                item(key = "bigh") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Maiores arquivos (50 MB+)", Modifier.weight(1f))
                        if (sel.isNotEmpty()) Text("${sel.size} • ${fmtBytes(big.orEmpty().filter { it.path in sel }.sumOf { it.size })}", style = MaterialTheme.typography.labelMedium)
                    }
                }
                val b = big
                if (b == null) item(key = "bigload") {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Procurando os arquivos grandes, inclusive escondidos…", style = MaterialTheme.typography.bodySmall) }
                } else if (b.isEmpty()) item(key = "bignone") { Text("Nenhum arquivo de 50 MB ou mais encontrado.", style = MaterialTheme.typography.bodySmall) }
                else items(b, key = { it.path }) { f ->
                    val inCloud = (f.name to f.size) in onCloud
                    val on = f.path in sel
                    val (icon, tone) = fileIcon(f.name)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable {
                            if (f.protected) Toast.makeText(ctx, "Arquivo protegido: faça backup dele em Álbuns → No celular (cadeado).", Toast.LENGTH_LONG).show()
                            else sel = if (on) sel - f.path else sel + f.path
                        }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(tone.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Icon(if (on) Icons.Rounded.CheckCircle else icon, null, tint = if (on) MaterialTheme.colorScheme.primary else tone)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text((if (f.protected) "🔒 " else "") + f.path.substringBeforeLast('/').removePrefix("/storage/emulated/0/"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmtBytes(f.size), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            if (inCloud) Text("✓ na nuvem", style = MaterialTheme.typography.labelSmall, color = Nv.Cyan)
                        }
                    }
                }
            }
            if (sel.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({
                    BackupQueue.addFiles(sel.toList()); val n = sel.size; sel = emptySet()
                    scope.launch(Dispatchers.IO) { Backup.runNow(ctx, true) }
                    Toast.makeText(ctx, "$n na fila do backup.", Toast.LENGTH_SHORT).show()
                }, Modifier.weight(1f)) { Icon(Icons.Rounded.CloudUpload, null); Spacer(Modifier.width(6.dp)); Text("Backup") }
                Button({
                    cleanup = big.orEmpty().filter { it.path in sel }.map { f ->
                        val file = File(f.path)
                        LocalItem(Uri.fromFile(file), f.path, f.name, f.size, f.mtime, f.mtime, f.path.substringBeforeLast('/').substringAfterLast('/'), "", FileScan.mimeOf(f.name))
                    }
                }, Modifier.weight(1f)) { Icon(Icons.Rounded.CleaningServices, null); Spacer(Modifier.width(6.dp)); Text("Liberar") }
            }
        }
    }
}

@Composable
private fun CatRow(icon: ImageVector, color: Color, title: String, value: String, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Icon(icon, null, Modifier.size(18.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (onClick != null) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp))
    }
}

/** Excluir aplicativos: lista por tamanho, seleção múltipla e exclusão em fila (com Shizuku, sem confirmar cada um). */
@Composable
fun AppsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val tick = resumeTick()
    val wm = remember { WorkManager.getInstance(ctx) }
    val work by wm.getWorkInfosForUniqueWorkFlow(Uninstall.WORK).collectAsStateWithLifecycle(emptyList())
    val prog by Uninstall.progress.collectAsStateWithLifecycle()
    val running = work.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    var apps by remember { mutableStateOf<List<Space.AppInfo>?>(null) }
    var sel by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf<List<String>>(emptyList()) }
    val shiz = remember(tick) { Shiz.state() }
    val usage = remember(tick) { Space.usageGranted(ctx) }
    LaunchedEffect(tick, prog?.done, running) { apps = withContext(Dispatchers.IO) { try { Space.apps(ctx) } catch (_: Exception) { emptyList() } } }
    // Sem Shizuku: o Android pede confirmação de cada app, um depois do outro.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { manual = manual.drop(1) }
    LaunchedEffect(manual) {
        val next = manual.firstOrNull() ?: return@LaunchedEffect
        try {
            launcher.launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$next")).putExtra(Intent.EXTRA_RETURN_RESULT, true))
        } catch (_: Exception) { manual = manual.drop(1) }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ScreenTop("Excluir aplicativos", onBack)
            if (running || (prog != null && prog!!.total > 0)) {
                val p = prog
                GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), padding = 14.dp) {
                    if (running && p != null) {
                        Text("Excluindo ${p.current} • ${p.done} de ${p.total}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator({ if (p.total > 0) p.done / p.total.toFloat() else 0f }, Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(CircleShape))
                        Text("Continua mesmo se você sair do app.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ Uninstall.cancel(ctx) }) { Text("Parar") }
                    } else if (p != null) {
                        Text("Pronto: ${p.done - p.failed.size} excluídos" + if (p.failed.isNotEmpty()) " • ${p.failed.size} não deu (${p.failed.take(3).joinToString()})" else "",
                            style = MaterialTheme.typography.titleSmall)
                        TextButton({ Uninstall.progress.value = null }) { Text("OK") }
                    } else Text("Preparando a exclusão…")
                }
            }
            if (shiz != ShizState.READY) Text(
                if (shiz == ShizState.NO_PERMISSION) "Dica: permita o Nuvem no Shizuku pra excluir tudo de uma vez, sem confirmar cada app."
                else "Sem o Shizuku, o Android pede confirmação de cada app (um depois do outro). Com o Shizuku ligado, exclui tudo sozinho em segundo plano.",
                style = MaterialTheme.typography.bodySmall, color = Nv.Gold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            if (shiz == ShizState.NO_PERMISSION) TextButton({ Shiz.requestPermission() }, Modifier.padding(horizontal = 8.dp)) { Text("Permitir no Shizuku") }
            if (!usage) Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Pra ver o tamanho real (com vídeos e arquivos que o app guarda), libere \"Acesso ao uso\".", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton({ try { ctx.startActivity(Space.usageIntent(ctx)) } catch (_: Exception) {} }) { Text("Liberar") }
            }
            val list = apps
            if (list == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                item { Text("${list.size} apps • ${fmtBytes(list.sumOf { it.bytes })}" + if (list.firstOrNull()?.sizeKnown == false) " (só o tamanho do app)" else "",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) }
                items(list, key = { it.pkg }) { a ->
                    val on = a.pkg in sel
                    val icon = remember(a.pkg) { try { ctx.packageManager.getApplicationIcon(a.pkg).toBitmap(96, 96).asImageBitmap() } catch (_: Exception) { null } }
                    Row(Modifier.fillMaxWidth().background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { sel = if (on) sel - a.pkg else sel + a.pkg }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { sel = if (on) sel - a.pkg else sel + a.pkg })
                        if (icon != null) Image(icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                        else Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(if (a.sizeKnown && a.dataBytes > 0) "dados ${fmtBytes(a.dataBytes)}" else a.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(fmtBytes(a.bytes), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (sel.isNotEmpty()) Button({ confirm = true }, Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Rounded.Delete, null); Spacer(Modifier.width(8.dp))
                Text("Excluir ${sel.size} ${if (sel.size == 1) "app" else "apps"} (${fmtBytes(apps.orEmpty().filter { it.pkg in sel }.sumOf { it.bytes })})")
            }
        }
    }
    if (confirm) AlertDialog({ confirm = false },
        title = { Text("Excluir ${sel.size} ${if (sel.size == 1) "app" else "apps"}?") },
        text = { Text(apps.orEmpty().filter { it.pkg in sel }.joinToString("\n") { "• ${it.label} (${fmtBytes(it.bytes)})" }.take(900) +
            "\n\nOs dados desses apps também saem do celular." +
            if (shiz == ShizState.READY) "\nVai um por um em segundo plano; pode sair do app." else "\nO Android vai pedir confirmação de cada um.") },
        confirmButton = { Button({
            confirm = false
            val pkgs = sel.toList(); sel = emptySet()
            if (shiz == ShizState.READY) { Uninstall.enqueue(ctx, pkgs); Toast.makeText(ctx, "Excluindo ${pkgs.size} apps em segundo plano.", Toast.LENGTH_SHORT).show() }
            else manual = pkgs
        }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Excluir") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancelar") } })
}
