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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.work.Backup
import br.geracaoai.nuvem.work.BatteryGuide
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
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Álbuns", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp)) }
            item(span = { GridItemSpan(maxLineSpan) }) { SecretAlbumsEntry(onOpen) }
            items(albums, key = { it.first }) { (name, list) ->
                GlassCard(onClick = { open = name }, radius = 20.dp, padding = 8.dp) {
                    Thumb(list.first(), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)))
                    Text(folderLabel(name), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
                    Text("${list.size} itens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                }
            }
        } else Column {
            Row(verticalAlignment = Alignment.CenterVertically) { IconButton({ open = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }; Text(o, style = MaterialTheme.typography.titleLarge) }
            GalleryScreen(o, onOpen = onOpen)
        }
    }
}

@Composable
fun SwitchRow(title: String, sub: String, v: Boolean, on: (Boolean) -> Unit) =
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(sub) }, trailingContent = { Switch(v, on) }, modifier = Modifier.clickable { on(!v) })


@Composable
fun SettingsLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, enabled: Boolean = true, tag: String? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier).clip(RoundedCornerShape(16.dp)).then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier).padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = Nv.Sky)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

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
    var destConfirm by remember { mutableStateOf(false) }
    var destResend by remember { mutableStateOf(false) }
    var showFree by remember { mutableStateOf(false) }
    val premium by Td.isPremium.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        try {
            val st = Td.send(TdApi.GetPasswordState(), 20_000)
            emailPattern = st.loginEmailAddressPattern.takeIf { it.isNotBlank() }
        } catch (_: Exception) {}
    }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 108.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Ajustes", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        item {
            SectionTitle("Conta")
            GlassCard(padding = 8.dp) {
                SettingsLine(Icons.Rounded.Cloud, "Destino", (Prefs.chatTitle.ifBlank { "—" }) + " • " + DestKind.label(DestKind.effective()) + if (premium) " • Premium (até 4 GB por arquivo)" else " • até 2 GB por arquivo", tag = FeatureTags.SETTINGS_DESTINO) { destConfirm = true; destResend = false }
                SettingsLine(Icons.Rounded.Email, "E-mail de login", if (emailPattern != null) "Cadastrado: $emailPattern. Códigos futuros podem chegar neste e-mail." else "O Telegram não entra com conta Google. Cadastre um e-mail pra receber o código de login.", tag = FeatureTags.SETTINGS_EMAIL, onClick = { emailDlg = true; emailErr = null; emailInfo = null; emailCode = "" })
                SettingsLine(Icons.Rounded.PhoneAndroid, "Sessão neste celular", "Sua conta fica salva aqui. Não precisa colocar o número de novo ao reabrir o app, só se sair da conta ou desinstalar.", tag = FeatureTags.SETTINGS_SESSAO)
            }
        }
        item {
            SectionTitle("Aparência")
            GlassCard(padding = 16.dp) {
                Text("Tema", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Sistema", "Claro", "Escuro").forEachIndexed { i, l ->
                        FilterChip(theme == i, { Prefs.setTheme(i) }, { Text(l) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Nv.Sky.copy(0.25f), selectedLabelColor = MaterialTheme.colorScheme.onSurface))
                    }
                }
            }
        }
        item {
            SectionTitle("Nuvem")
            GlassCard(padding = 8.dp) {
                var cleaning by remember { mutableStateOf(false) }
                var cleanMsg by remember { mutableStateOf<String?>(null) }
                SettingsLine(Icons.Rounded.CleaningServices, "Limpar legendas antigas", cleanMsg ?: if (cleaning) "Editando mensagens…" else "Tira o texto enorme das que já subiram e deixa só a hashtag curta", enabled = !cleaning, tag = FeatureTags.SETTINGS_LEGENDAS) {
                    cleaning = true; cleanMsg = null
                    scope.launch {
                        try {
                            val n = Repo.clearOldCaptions()
                            cleanMsg = if (n == 0) "Nenhuma legenda antiga encontrada." else "Limpas $n legendas."
                        } catch (e: Exception) { cleanMsg = traduz(e.message) }
                        cleaning = false
                    }
                }
                SettingsLine(Icons.Rounded.CleaningServices, "Liberar espaço", "Arquivos já no Telegram vão para a lixeira do celular", tag = FeatureTags.SETTINGS_LIBERAR) { showFree = true }
                Column(Modifier.testTag(FeatureTags.SETTINGS_DUPLICADAS)) { DupCleanRow() }
                SecretSettingsRow()
                SettingsLine(Icons.Rounded.Sync, "Reindexar nuvem", if (syncing) "Sincronizando…" else "Relê todo o canal (útil se apagou algo pelo Telegram)", enabled = !syncing, tag = FeatureTags.SETTINGS_REINDEXAR) {
                    syncing = true; scope.launch { try { Repo.ensureDestination(); Repo.syncIndex(full = true) } catch (e: Exception) { Prefs.lastError = e.message ?: "erro" }; syncing = false }
                }
            }
        }
        item {
            SectionTitle("Sessão")
            GlassCard(padding = 8.dp) {
                SettingsLine(Icons.Rounded.SwapHoriz, "Trocar destino", "Escolher outro grupo, grupo com tópicos, canal ou Mensagens Salvas", tag = FeatureTags.SETTINGS_TROCAR_DESTINO) { destConfirm = true; destResend = false }
                SettingsLine(Icons.AutoMirrored.Rounded.Logout, "Sair / trocar de conta", "Entrar com outro número. Os arquivos continuam no Telegram.", tag = FeatureTags.SETTINGS_SAIR) { confirmOut = true }
            }
        }
        item { Text("Nuvem 1.6.2 • seus arquivos ficam na sua conta do Telegram. Nada passa por servidores de terceiros. Não é possível entrar com conta Google: o Telegram sempre usa um número.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
    }
    if (showFree) FreeUpSheet({ showFree = false })
    if (destConfirm) AlertDialog(
        { destConfirm = false },
        title = { Text("Trocar destino?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Os arquivos que já foram enviados (neste ou em outro grupo, canal ou nas Mensagens Salvas) não serão reenviados, a menos que você marque a opção abaixo. O app reconhece o arquivo e só sobe o que ainda não está na sua conta.")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { destResend = !destResend }) {
                    Checkbox(destResend, { destResend = it })
                    Text("Reenviar também o que já está em outro destino", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { Button({ Prefs.resendExisting = destResend; destConfirm = false; Prefs.clearDest(); onLogout() }) { Text("Continuar") } },
        dismissButton = { TextButton({ destConfirm = false }) { Text("Cancelar") } },
    )
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
        GlassCard(onClick = { unlock() }, padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlowIcon(Icons.Rounded.Lock, Nv.Violet, Nv.SkyDeep, 44.dp); Spacer(Modifier.width(12.dp))
                Column { Text("Álbum secreto", style = MaterialTheme.typography.titleMedium); Text("Pastas protegidas por PIN ou digital", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
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
fun SamsungBatteryCard() {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(BatteryGuide.shouldShowCard(ctx)) }
    val life = LocalLifecycleOwner.current
    DisposableEffect(life) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) show = BatteryGuide.shouldShowCard(ctx) }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }
    if (!show) return
    GlassCard(padding = 18.dp) {
        Text("Pra o backup não parar no Samsung", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("O celular costuma matar o backup com a tela desligada. Faz isso uma vez:", style = MaterialTheme.typography.bodyMedium)
        Text("1. Toque em Permitir segundo plano e aceite não otimizar a bateria do Nuvem.")
        Text("2. Vá em Configurações > Bateria > Limites de uso em segundo plano > Apps que nunca suspendem e adicione o Nuvem.")
        Text("3. Nas informações do app, em Bateria, escolha Sem restrições.")
        Text("Deixe as notificações permitidas pra acompanhar o progresso e o backup continuar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        PillButton("Permitir segundo plano", Icons.Rounded.BatteryChargingFull, { BatteryGuide.openBest(ctx) })
        TextButton({ Prefs2.batteryGuideDismissed = true; show = false }) { Text("Já configurei") }
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
            title = { Text("Apagar duplicadas no Telegram?") },
            text = { Text("${p.preview}. Fica só a cópia mais antiga de cada um, e o arquivo original da foto se existir. Isso apaga as outras mensagens no Telegram de forma permanente (não vai para a lixeira do celular). Confirme só se tiver certeza.") },
            confirmButton = {
                Button({
                    confirm = false; deleting = true; progress = "Apagando…"
                    scope.launch {
                        try {
                            val n = withContext(Dispatchers.IO) { Repo.deleteDuplicates(p) { d, t -> progress = "Apagando $d de $t" } }
                            ActionLog.add("duplicadas", "Apagou $n mensagens duplicadas no Telegram (permanente). Ficou a cópia mais antiga.", n, 0)
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
