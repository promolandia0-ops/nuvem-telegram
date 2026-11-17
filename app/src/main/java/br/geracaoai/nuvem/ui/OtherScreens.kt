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
    LaunchedEffect(ver) { albums = withContext(Dispatchers.IO) { Db.allCloudMany(Prefs.galleryChats()).groupBy { it.folder }.toList().sortedByDescending { it.second.size } } }
    BackHandler(open != null) { open = null }
    AnimatedContent(open, transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut() }, label = "alb") { o ->
        if (o == null) LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Álbuns por pasta", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(4.dp)) }
            item(span = { GridItemSpan(maxLineSpan) }) { SecretAlbumsEntry(onOpen) }
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
    var origPhotos by remember { mutableStateOf(Prefs.originalPhotos) }
    LaunchedEffect(Unit) { buckets = withContext(Dispatchers.IO) { try { LocalMedia.buckets(ctx) } catch (_: Exception) { emptyList() } } }
    fun save() { Prefs.folders = sel; Prefs.autoBackup = auto; Prefs.wifiOnly = wifi; Prefs.chargingOnly = charging; Prefs.originalPhotos = origPhotos; Backup.schedule(ctx) }
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
                            val step = r.progress.getString("name") ?: "verificando…"
                            val err = r.progress.getString("err")?.takeIf { it.isNotBlank() && !isCancelMessage(it) }
                            Column {
                                Text(if (t == 0) step else "$d de $t • $step", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(6.dp)); LinearProgressIndicator({ if (t == 0) 0f else d / t.toFloat() }, Modifier.fillMaxWidth())
                                Spacer(Modifier.height(4.dp)); LinearProgressIndicator({ p / 100f }, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiary)
                                err?.let { Spacer(Modifier.height(8.dp)); Text("Erro: ${traduz(it)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                        } else Text(buildString {
                            if (paused) append("Parado por você. Nada será enviado até tocar em Retomar.\n")
                            append(if (Prefs.lastBackup > 0) "Último backup: " + SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(Prefs.lastBackup)) else "Nenhum backup ainda")
                            if (Prefs.lastError.isNotBlank() && !isCancelMessage(Prefs.lastError)) append("\nÚltimo erro: ${traduz(Prefs.lastError)}")
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
        item { DupCleanRow() }
        item { Text("Opções", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        item { SwitchRow("Backup automático", "A cada hora, em segundo plano", auto) { auto = it; save() } }
        item { SwitchRow("Somente no Wi-Fi", "Não usa dados móveis", wifi) { wifi = it; save() } }
        item { SwitchRow("Somente carregando", "Economiza bateria", charging) { charging = it; save() } }
        item { SwitchRow("Fotos em qualidade original", "Envia a foto pra ver no Telegram e também o arquivo original, sem compressão", origPhotos) { origPhotos = it; save() } }
        item {
            var instant by remember { mutableStateOf(Prefs.instantBackup) }
            SwitchRow("Backup instantâneo", "Sobe sozinho quando entra foto ou vídeo novo na galeria", instant) { instant = it; Prefs.instantBackup = it }
        }
        item { AutoFreeRow() }
        item { Text("Pastas para backup", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        item { Text("Toque no cadeado pra mandar a pasta ao canal secreto (protegido por PIN ou digital).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (buckets.isEmpty()) item { Text("Permita o acesso a fotos e vídeos para listar as pastas.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(buckets, key = { it.name }) { b ->
            var secretSet by remember { mutableStateOf(Prefs.secretFolders) }
            val locked = b.name in secretSet
            ListItem(headlineContent = { Text(b.name.ifBlank { "(sem nome)" }) }, supportingContent = { Text("${b.count} itens" + if (locked) " • secreto" else "") },
                leadingContent = { Icon(if (b.name.equals("Camera", true)) Icons.Rounded.PhotoCamera else Icons.Rounded.Folder, null) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({
                            val next = if (locked) secretSet - b.name else secretSet + b.name
                            secretSet = next; Prefs.secretFolders = next
                            if (!locked && b.name !in sel) { sel = sel + b.name; save() }
                        }) { Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, if (locked) "Secreto" else "Normal") }
                        Checkbox(b.name in sel || locked, { c -> sel = if (c) sel + b.name else sel - b.name; save() })
                    }
                },
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
    var emailPattern by remember { mutableStateOf<String?>(null) }
    var emailDlg by remember { mutableStateOf(false) }
    var emailAddr by remember { mutableStateOf("") }
    var emailCode by remember { mutableStateOf("") }
    var emailInfo by remember { mutableStateOf<TdApi.EmailAddressAuthenticationCodeInfo?>(null) }
    var emailErr by remember { mutableStateOf<String?>(null) }
    var emailBusy by remember { mutableStateOf(false) }
    val premium by Td.isPremium.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        try {
            val st = Td.send(TdApi.GetPasswordState(), 20_000)
            emailPattern = st.loginEmailAddressPattern.takeIf { it.isNotBlank() }
        } catch (_: Exception) {}
    }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item { Text("Ajustes", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp)) }
        item { ListItem(headlineContent = { Text("Destino") }, supportingContent = { Text(Prefs.chatTitle.ifBlank { "—" } + if (premium) " • Premium (até 4 GB por arquivo)" else " • até 2 GB por arquivo") }, leadingContent = { Icon(Icons.Rounded.Cloud, null) }) }
        item {
            ListItem(headlineContent = { Text("Tema") }, leadingContent = { Icon(Icons.Rounded.Palette, null) }, supportingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Sistema", "Claro", "Escuro").forEachIndexed { i, l -> FilterChip(theme == i, { Prefs.setTheme(i) }, { Text(l) }) } }
            })
        }
        item { ListItem(headlineContent = { Text("E-mail de login") },
            supportingContent = { Text(if (emailPattern != null) "Cadastrado: $emailPattern. Códigos futuros podem chegar neste e-mail." else "O Telegram não entra com conta Google. Cadastre um e-mail pra receber o código de login.") },
            leadingContent = { Icon(Icons.Rounded.Email, null) }, modifier = Modifier.clickable { emailDlg = true; emailErr = null; emailInfo = null; emailCode = "" }) }
        item { ListItem(headlineContent = { Text("Sessão neste celular") }, supportingContent = { Text("Sua conta fica salva aqui. Não precisa colocar o número de novo ao reabrir o app, só se sair da conta ou desinstalar.") }, leadingContent = { Icon(Icons.Rounded.PhoneAndroid, null) }) }
        item {
            var cleaning by remember { mutableStateOf(false) }
            var cleanMsg by remember { mutableStateOf<String?>(null) }
            ListItem(headlineContent = { Text("Limpar legendas antigas") }, supportingContent = { Text(cleanMsg ?: if (cleaning) "Editando mensagens…" else "Tira o texto enorme das que já subiram e deixa só a hashtag curta") },
                leadingContent = { Icon(Icons.Rounded.CleaningServices, null) },
                modifier = Modifier.clickable(enabled = !cleaning) {
                    cleaning = true; cleanMsg = null
                    scope.launch {
                        try {
                            val n = Repo.clearOldCaptions()
                            cleanMsg = if (n == 0) "Nenhuma legenda antiga encontrada." else "Limpas $n legendas."
                        } catch (e: Exception) { cleanMsg = traduz(e.message) }
                        cleaning = false
                    }
                })
        }
        item { DupCleanRow() }
        item { SecretSettingsRow() }
        item { ListItem(headlineContent = { Text("Reindexar nuvem") }, supportingContent = { Text(if (syncing) "Sincronizando…" else "Relê todo o canal (útil se apagou algo pelo Telegram)") },
            leadingContent = { Icon(Icons.Rounded.Sync, null) }, modifier = Modifier.clickable(enabled = !syncing) { syncing = true; scope.launch { try { Repo.ensureDestination(); Repo.syncIndex(full = true) } catch (e: Exception) { Prefs.lastError = e.message ?: "erro" }; syncing = false } }) }
        item { ListItem(headlineContent = { Text("Trocar destino") }, supportingContent = { Text("Escolher outro grupo, canal ou Mensagens Salvas") }, leadingContent = { Icon(Icons.Rounded.SwapHoriz, null) },
            modifier = Modifier.clickable { Prefs.clearDest(); onLogout() }) }
        item { ListItem(headlineContent = { Text("Sair / trocar de conta") }, supportingContent = { Text("Entrar com outro número. Os arquivos continuam no Telegram.") }, leadingContent = { Icon(Icons.AutoMirrored.Rounded.Logout, null) }, modifier = Modifier.clickable { confirmOut = true }) }
        item { Text("Nuvem 1.4.2 • seus arquivos ficam na sua conta do Telegram. Nada passa por servidores de terceiros. Não é possível entrar com conta Google: o Telegram sempre usa um número.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
    }
    if (emailDlg) AlertDialog({ emailDlg = false }, title = { Text("E-mail de login") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Isso não substitui o número e não é login com o Google. O Telegram pode enviar o código de verificação para este e-mail nas próximas vezes.")
                if (emailInfo == null) {
                    OutlinedTextField(emailAddr, { emailAddr = it.trim() }, label = { Text("E-mail") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Enviamos um código para ${emailInfo!!.emailAddressPattern}.")
                    OutlinedTextField(emailCode, { emailCode = it.filter(Char::isDigit) }, label = { Text("Código") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                emailErr?.let { Text(traduz(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button({
                emailBusy = true; emailErr = null
                scope.launch {
                    try {
                        if (emailInfo == null) {
                            val info = Td.send(TdApi.SetLoginEmailAddress(emailAddr), 30_000)
                            emailInfo = info
                        } else {
                            Td.send(TdApi.CheckLoginEmailAddressCode(TdApi.EmailAddressAuthenticationCode(emailCode)), 30_000)
                            emailPattern = emailAddr
                            emailDlg = false
                        }
                    } catch (e: Exception) { emailErr = e.message }
                    emailBusy = false
                }
            }, enabled = !emailBusy && (if (emailInfo == null) emailAddr.contains("@") else emailCode.length >= 4)) { Text(if (emailInfo == null) "Enviar código" else "Confirmar") }
        },
        dismissButton = { TextButton({ emailDlg = false }) { Text("Cancelar") } })
    if (confirmOut) AlertDialog({ confirmOut = false }, title = { Text("Sair da conta?") }, text = { Text("Seus arquivos continuam no Telegram. Depois você pode entrar com o mesmo número ou com outro. O backup automático para até você entrar de novo.") },
        confirmButton = { Button({ confirmOut = false; scope.launch { try { Td.send(TdApi.LogOut()) } catch (_: Exception) {}; Prefs.clearAccount(); SecretLock.lock(); onLogout() } }) { Text("Sair") } },
        dismissButton = { TextButton({ confirmOut = false }) { Text("Cancelar") } })
}

@Composable
fun AutoFreeRow() {
    var days by remember { mutableIntStateOf(Prefs.autoFreeDays) }
    var confirm by remember { mutableStateOf<Int?>(null) }
    Column {
        Text("Liberar espaço automático", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        Text("Desligado por padrão. Quando ligado, o app sugere apagar do celular o que já está confirmado na nuvem há mais de X dias (o Android ainda pede sua confirmação).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Desligado", 7 to "7 dias", 30 to "30 dias", 90 to "90 dias").forEach { (d, l) ->
                FilterChip(days == d, { if (d == 0) { days = 0; Prefs.autoFreeDays = 0 } else confirm = d }, { Text(l) })
            }
        }
    }
    confirm?.let { d ->
        AlertDialog({ confirm = null }, title = { Text("Ligar liberação automática?") },
            text = { Text("Depois de $d dias, o que já estiver confirmado na nuvem pode ser apagado deste celular. Nada some do Telegram. O Android ainda pede pra você confirmar a exclusão.") },
            confirmButton = { Button({ Prefs.autoFreeDays = d; days = d; confirm = null }) { Text("Ligar") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancelar") } })
    }
}

@Composable
fun SecretSettingsRow() {
    var show by remember { mutableStateOf(false) }
    ListItem(headlineContent = { Text("PIN do canal secreto") },
        supportingContent = { Text(if (SecretLock.hasPin()) "PIN já definido. Toque pra trocar." else "Defina um PIN pra proteger as pastas secretas.") },
        leadingContent = { Icon(Icons.Rounded.Lock, null) },
        modifier = Modifier.clickable { show = true })
    if (show) SecretPinDialog(setup = true, onUnlocked = { show = false }, onDismiss = { show = false })
}


@Composable
fun SecretAlbumsEntry(onOpen: (List<CloudItem>, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val unlock = rememberUnlockSecret { open = true }
    val ver by Db.version.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<CloudItem>>(emptyList()) }
    LaunchedEffect(ver, open, SecretLock.unlocked) {
        items = if (open && SecretLock.unlocked && Prefs.secretChatId != 0L)
            withContext(Dispatchers.IO) { Db.allCloud(Prefs.secretChatId) } else emptyList()
    }
    if (!open) {
        ListItem(headlineContent = { Text("Álbum secreto") }, supportingContent = { Text("Pastas protegidas por PIN ou digital") },
            leadingContent = { Icon(Icons.Rounded.Lock, null) },
            modifier = Modifier.clickable { unlock() })
    } else {
        Column {
            ListItem(headlineContent = { Text("Álbum secreto") }, supportingContent = { Text("${items.size} itens") },
                leadingContent = { Icon(Icons.Rounded.LockOpen, null) },
                trailingContent = { TextButton({ open = false; SecretLock.lock() }) { Text("Bloquear") } })
            if (items.isEmpty()) Text("Nada no secreto ainda. Marque pastas com o cadeado em Backup.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.height(320.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(items.size, key = { items[it].msgId }) { idx ->
                    Thumb(items[idx], Modifier.aspectRatio(1f).clickable { onOpen(items, idx) })
                }
            }
        }
    }
}

@Composable
fun DupCleanRow() {
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var scanHint by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<DupPlan?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    ListItem(
        headlineContent = { Text("Apagar duplicadas do Telegram") },
        supportingContent = { Text(when {
            deleting -> progress.ifBlank { "Apagando duplicadas…" }
            scanning -> progress.ifBlank { "Procurando fotos, vídeos e arquivos repetidos…" }
            result != null -> result!!
            scanHint != null -> scanHint!!
            else -> "Acha fotos, vídeos e originais repetidos e apaga, deixando a cópia mais antiga."
        }) },
        leadingContent = { Icon(Icons.Rounded.ContentCopy, null) },
        modifier = Modifier.clickable(enabled = !scanning && !deleting) {
            scanning = true; result = null; plan = null; progress = "Lendo o Telegram…"
            scope.launch {
                try {
                    val p = withContext(Dispatchers.IO) { Repo.scanDuplicates { n -> progress = "Lendo… $n itens" } }
                    plan = p
                    scanHint = p.preview
                    if (p.delete.isNotEmpty()) confirm = true
                } catch (e: Exception) { result = traduz(e.message) }
                scanning = false; progress = ""
            }
        })
    if (confirm && plan != null) {
        val p = plan!!
        AlertDialog(
            { confirm = false },
            title = { Text("Apagar duplicadas?") },
            text = { Text("${p.preview}. Fica só a cópia mais antiga de cada um, e o arquivo original da foto se existir. Isso apaga as outras mensagens no Telegram.") },
            confirmButton = {
                Button({
                    confirm = false; deleting = true; progress = "Apagando…"
                    scope.launch {
                        try {
                            val n = withContext(Dispatchers.IO) { Repo.deleteDuplicates(p) { d, t -> progress = "Apagando $d de $t" } }
                            result = "Apagadas $n duplicadas."
                            plan = null; scanHint = null
                        } catch (e: Exception) { result = traduz(e.message) }
                        deleting = false; progress = ""
                    }
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancelar") } }
        )
    }
}
