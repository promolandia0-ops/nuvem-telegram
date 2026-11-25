package br.geracaoai.nuvem.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.work.Backup
import br.geracaoai.nuvem.work.Restore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val fileDateFmt = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))

fun fileIcon(name: String, mime: String = ""): Pair<ImageVector, Color> {
    val m = mime.ifBlank { FileScan.mimeOf(name) }
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        m.startsWith("image/") -> Icons.Rounded.Image to Color(0xFF4FC3F7)
        m.startsWith("video/") -> Icons.Rounded.Movie to Color(0xFFBA68C8)
        m.startsWith("audio/") -> Icons.Rounded.AudioFile to Color(0xFFFF8A65)
        ext == "pdf" -> Icons.Rounded.PictureAsPdf to Color(0xFFE57373)
        ext in setOf("zip", "rar", "7z", "tar", "gz") -> Icons.Rounded.FolderZip to Color(0xFFFFB74D)
        ext == "apk" -> Icons.Rounded.Android to Color(0xFF81C784)
        ext in setOf("doc", "docx", "odt", "txt", "rtf", "md") -> Icons.Rounded.Description to Color(0xFF64B5F6)
        ext in setOf("xls", "xlsx", "ods", "csv") -> Icons.Rounded.TableChart to Color(0xFF66BB6A)
        ext in setOf("ppt", "pptx", "odp") -> Icons.Rounded.Slideshow to Color(0xFFFF7043)
        else -> Icons.Rounded.InsertDriveFile to Color(0xFF90A4AE)
    }
}

fun openLocalFile(ctx: Context, file: File, mime: String = "") {
    try {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifBlank { FileScan.mimeOf(file.name) })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(Intent.createChooser(i, "Abrir com").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(ctx, "Nenhum app abre esse tipo de arquivo.", Toast.LENGTH_SHORT).show()
    }
}

/** Aba Arquivos: navegar nas pastas do celular (e mandar pasta inteira pro backup) ou ver o que está na nuvem. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(actions: @Composable RowScope.() -> Unit, onOpenMedia: (List<CloudItem>, Int) -> Unit, banner: @Composable () -> Unit = {}) {
    var side by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Arquivos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            actions()
        }
        banner()
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            SegmentedButton(side == 0, { side = 0 }, SegmentedButtonDefaults.itemShape(0, 3), icon = {}) { Text("Celular", maxLines = 1) }
            SegmentedButton(side == 1, { side = 1 }, SegmentedButtonDefaults.itemShape(1, 3), icon = {}) { Text("Nuvem", maxLines = 1) }
            SegmentedButton(side == 2, { side = 2 }, SegmentedButtonDefaults.itemShape(2, 3), icon = {}) { Text("MEGA", maxLines = 1) }
        }
        when (side) {
            0 -> PhoneBrowser()
            1 -> CloudBrowser(onOpenMedia)
            else -> MegaPane()
        }
    }
}

@Composable
private fun PhoneBrowser() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { LocalMedia.storageRoots().firstOrNull() ?: File("/storage/emulated/0") }
    var path by rememberSaveable { mutableStateOf(root.absolutePath) }
    val dir = File(path)
    var access by remember { mutableStateOf(AllFilesAccess.granted()) }
    var entries by remember { mutableStateOf<List<FileScan.Entry>?>(null) }
    var foldersVer by remember { mutableIntStateOf(0) }
    var ask by remember { mutableStateOf<File?>(null) }
    var askRemove by remember { mutableStateOf<File?>(null) }
    val life = LocalLifecycleOwner.current
    DisposableEffect(life) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access = AllFilesAccess.granted() }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(path, access) {
        entries = null
        entries = withContext(Dispatchers.IO) { FileScan.list(dir) }
    }
    val atRoot = dir.absolutePath.trimEnd('/') == root.absolutePath.trimEnd('/')
    BackHandler(!atRoot) { path = dir.parentFile?.absolutePath ?: root.absolutePath }

    if (!access) {
        GlassCard(Modifier.padding(16.dp), padding = 16.dp) {
            Icon(Icons.Rounded.FolderOpen, null, tint = Nv.Gold)
            Spacer(Modifier.height(8.dp))
            Text("Liberar acesso às pastas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Pra ver e salvar qualquer arquivo (PDF, ZIP, documentos, áudio…), o Android pede a permissão \"Acesso a todos os arquivos\".",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button({ try { ctx.startActivity(AllFilesAccess.intent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }, Modifier.fillMaxWidth()) { Text("Liberar acesso") }
        }
        return
    }

    val covered = remember(path, foldersVer) { FileScan.isCovered(path) }
    val allOn = remember(foldersVer) { FileScan.allPhone }
    var askAll by remember { mutableStateOf(false) }
    var askAllOff by remember { mutableStateOf(false) }
    // Um toque: o celular inteiro vai pro backup (todas as pastas, qualquer tipo de arquivo).
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(if (allOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer)
            .clickable { if (allOn) askAllOff = true else askAll = true }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (allOn) Icons.Rounded.CloudDone else Icons.Rounded.CloudUpload, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (allOn) "Todos os arquivos estão no backup" else "Fazer backup de todos os arquivos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(if (allOn) "Arquivos novos sobem sozinhos. Toque pra desligar." else "Um toque: todas as pastas do celular, qualquer tipo de arquivo.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!allOn) Button({ askAll = true }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Fazer") }
    }
    val freeSpace = rememberFreeSpace()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).clickable { freeSpace() }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CleaningServices, null, tint = Nv.Gold)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Liberar espaço", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Apaga do celular o que já está na nuvem (arquivos, fotos e vídeos). Mostra os GB antes.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null)
    }
    if (askAll) AlertDialog({ askAll = false },
        icon = { Icon(Icons.Rounded.CloudUpload, null) },
        title = { Text("Fazer backup de todos os arquivos?") },
        text = { Text("Todas as pastas do celular vão pra nuvem: documentos, downloads, WhatsApp, PDFs, ZIPs, áudios… Cada pasta principal vira um álbum. Fica de fora só a pasta Android (dados de apps) e pastas ocultas. Arquivos novos também sobem sozinhos.\n\nPode levar horas na primeira vez; roda em segundo plano.") },
        confirmButton = { Button({
            Prefs.fileFolders = Prefs.fileFolders + FileScan.rootPath()
            foldersVer++; askAll = false
            scope.launch {
                val busy = withContext(Dispatchers.IO) { Backup.isWorkRunning(ctx).also { Backup.runNow(ctx, true) } }
                Toast.makeText(ctx, if (busy) "Tem um backup rodando: os arquivos entram logo em seguida. Acompanhe na faixa lá em cima." else "Backup de todos os arquivos começou. Acompanhe na faixa lá em cima.", Toast.LENGTH_LONG).show()
            }
        }) { Text("Fazer backup") } },
        dismissButton = { TextButton({ askAll = false }) { Text("Cancelar") } })
    if (askAllOff) AlertDialog({ askAllOff = false },
        title = { Text("Parar o backup de todos os arquivos?") },
        text = { Text("Arquivos novos param de subir (as pastas que você marcou uma por uma continuam). O que já foi enviado continua na nuvem.") },
        confirmButton = { Button({
            Prefs.fileFolders = Prefs.fileFolders.filterNot { FileScan.isRoot(it) }.toSet()
            foldersVer++; askAllOff = false
        }) { Text("Parar") } },
        dismissButton = { TextButton({ askAllOff = false }) { Text("Cancelar") } })
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!atRoot) IconButton({ path = dir.parentFile?.absolutePath ?: root.absolutePath }) { Icon(Icons.Rounded.ArrowUpward, "Pasta de cima") }
        else Spacer(Modifier.width(12.dp))
        val crumbs = ("Celular/" + FileScan.relOf(path)).trimEnd('/').replace("/", "  ›  ")
        Text(crumbs, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (!atRoot) {
            if (covered) AssistChip({ if (path in Prefs.fileFolders) askRemove = dir }, { Text("No backup") }, leadingIcon = { Icon(Icons.Rounded.CloudDone, null, Modifier.size(16.dp)) })
            else AssistChip({ ask = dir }, { Text("Salvar pasta") }, leadingIcon = { Icon(Icons.Rounded.CloudUpload, null, Modifier.size(16.dp)) })
        }
    }
    val list = entries
    if (list == null) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (list.isEmpty()) {
        Text("Pasta vazia", Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { it.file.absolutePath }) { e ->
            if (e.isDir) {
                val inBackup = remember(e.file.absolutePath, foldersVer) { FileScan.isCovered(e.file.absolutePath) }
                FileRow(Icons.Rounded.Folder, Nv.Gold, e.file.name, "${e.children} ${if (e.children == 1) "item" else "itens"}", onClick = { path = e.file.absolutePath }) {
                    IconButton({
                        if (!inBackup) ask = e.file
                        else if (e.file.absolutePath in Prefs.fileFolders) askRemove = e.file
                        else Toast.makeText(ctx, "Já está no backup por uma pasta acima.", Toast.LENGTH_SHORT).show()
                    }) {
                        if (inBackup) Icon(Icons.Rounded.CloudDone, "No backup", tint = MaterialTheme.colorScheme.primary)
                        else Icon(Icons.Rounded.CloudUpload, "Salvar pasta na nuvem", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                val (icon, tone) = fileIcon(e.file.name)
                FileRow(icon, tone, e.file.name, "${fmtBytes(e.size)} • ${fileDateFmt.format(Date(e.mtime))}", onClick = { openLocalFile(ctx, e.file) }) {}
            }
        }
    }
    ask?.let { f ->
        AlertDialog({ ask = null },
            icon = { Icon(Icons.Rounded.CloudUpload, null) },
            title = { Text("Salvar \"${f.name}\" na nuvem?") },
            text = { Text("A pasta inteira vai pro backup, com subpastas e qualquer tipo de arquivo. Arquivos novos que entrarem nela também sobem.") },
            confirmButton = { Button({
                Prefs.fileFolders = Prefs.fileFolders + f.absolutePath
                foldersVer++; ask = null
                scope.launch {
                    val busy = withContext(Dispatchers.IO) { Backup.isWorkRunning(ctx).also { Backup.runNow(ctx, true) } }
                    Toast.makeText(ctx, if (busy) "Tem um backup rodando: ${f.name} entra logo em seguida." else "Backup de ${f.name} começou. Acompanhe na faixa lá em cima.", Toast.LENGTH_LONG).show()
                }
            }) { Text("Salvar") } },
            dismissButton = { TextButton({ ask = null }) { Text("Cancelar") } })
    }
    askRemove?.let { f ->
        AlertDialog({ askRemove = null },
            title = { Text("Tirar \"${f.name}\" do backup?") },
            text = { Text("Arquivos novos dessa pasta não sobem mais. O que já foi enviado continua na nuvem.") },
            confirmButton = { Button({ Prefs.fileFolders = Prefs.fileFolders - f.absolutePath; foldersVer++; askRemove = null }) { Text("Tirar") } },
            dismissButton = { TextButton({ askRemove = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun FileRow(icon: ImageVector, tone: Color, title: String, sub: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(tone.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tone)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        trailing()
    }
}

@Composable
private fun CloudBrowser(onOpenMedia: (List<CloudItem>, Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ver by Db.version.collectAsStateWithLifecycle()
    var all by remember { mutableStateOf<List<CloudItem>?>(null) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<CloudItem?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmFolder by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ver) { all = withContext(Dispatchers.IO) { try { Db.allCloudMany(Prefs.galleryChats()) } catch (_: Exception) { emptyList() } } }
    BackHandler(open != null) { open = null }
    val list = all
    if (list == null) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val groups = remember(list) { list.groupBy { it.folder.ifBlank { "Outros" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    val folder = open
    if (folder == null) {
        if (groups.isEmpty()) { EmptyCloud("Nada na nuvem ainda", "Escolha uma pasta em \"Celular\" e toque na nuvem."); return }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(groups.keys.toList(), key = { "f$it" }) { name ->
                val items = groups[name].orEmpty()
                val docs = items.count { !it.isVideo && !it.mime.startsWith("image/") }
                val sub = "${items.size} ${if (items.size == 1) "arquivo" else "arquivos"} • ${fmtBytes(items.sumOf { it.size })}" + if (docs > 0) " • $docs documentos" else ""
                FileRow(Icons.Rounded.FolderSpecial, MaterialTheme.colorScheme.primary, folderLabel(name), sub, onClick = { open = name }) {
                    IconButton({ confirmFolder = name }) { Icon(Icons.Rounded.Download, "Restaurar pasta") }
                }
            }
        }
    } else {
        val items = groups[folder].orEmpty()
        val media = items.filter { it.isVideo || it.mime.startsWith("image/") }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ open = null }) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
            Text(folderLabel(folder), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton({ confirmFolder = folder }) { Text("Restaurar") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(items, key = { "${it.chatId}:${it.msgId}" }) { ci ->
                val isMedia = ci.isVideo || ci.mime.startsWith("image/")
                val (icon, tone) = fileIcon(ci.name, ci.mime)
                val sub = "${fmtBytes(ci.size)} • ${fileDateFmt.format(Date(ci.taken))}" + if (busy == "${ci.msgId}") " • baixando…" else ""
                Row(Modifier.fillMaxWidth().clickable {
                    if (isMedia) onOpenMedia(media, media.indexOf(ci).coerceAtLeast(0)) else menu = ci
                }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isMedia) Thumb(ci, Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)))
                    else Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(tone.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tone) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ci.name.ifBlank { "arquivo" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton({ menu = ci }) { Icon(Icons.Rounded.MoreVert, "Opções") }
                }
            }
        }
    }
    menu?.let { ci ->
        AlertDialog({ menu = null },
            title = { Text(ci.name.ifBlank { "Arquivo" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("${fmtBytes(ci.size)} • ${folderLabel(ci.folder)}") },
            confirmButton = { Button({
                menu = null; busy = "${ci.msgId}"
                scope.launch {
                    val p = withContext(Dispatchers.IO) { try { Repo.fullPath(ci) } catch (_: Exception) { null } }
                    busy = null
                    if (p != null) openLocalFile(ctx, File(p), ci.mime) else Toast.makeText(ctx, "Não deu pra baixar agora.", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Abrir") } },
            dismissButton = { TextButton({
                menu = null
                Restore.enqueue(ctx, listOf(ci))
                Toast.makeText(ctx, "Restaurando ${ci.name} no celular.", Toast.LENGTH_SHORT).show()
            }) { Text("Restaurar no celular") } })
    }
    confirmFolder?.let { name ->
        val items = groups[name].orEmpty()
        AlertDialog({ confirmFolder = null },
            title = { Text("Restaurar ${folderLabel(name)}?") },
            text = { Text("${items.size} arquivos voltam pro celular, no lugar de onde saíram. Nada é apagado da nuvem.") },
            confirmButton = { Button({ confirmFolder = null; Restore.enqueue(ctx, items); Toast.makeText(ctx, "Restauração começou.", Toast.LENGTH_SHORT).show() }) { Text("Restaurar") } },
            dismissButton = { TextButton({ confirmFolder = null }) { Text("Cancelar") } })
    }
}
