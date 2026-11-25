package br.geracaoai.nuvem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import br.geracaoai.nuvem.data.Db
import br.geracaoai.nuvem.mega.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aba MEGA como navegador: o site do MEGA aqui dentro. Você entra normalmente;
 * com a sessão aberta, o painel de baixo manda os arquivos direto pro Telegram, sem ocupar o celular.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MegaPane() {
    val status by MegaWeb.status.collectAsStateWithLifecycle()
    val prog by MegaWeb.progress.collectAsStateWithLifecycle()
    val sent by MegaWeb.sentCount.collectAsStateWithLifecycle()
    val loading by MegaWeb.loading.collectAsStateWithLifecycle()
    val session by MegaWeb.session.collectAsStateWithLifecycle()
    val canBack by MegaWeb.canBack.collectAsStateWithLifecycle()
    var help by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(canBack) { MegaWeb.goBack() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ MegaWeb.goBack() }) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
            IconButton({ MegaWeb.home() }) { Icon(Icons.Rounded.Home, "Início do MEGA") }
            IconButton({ MegaWeb.reload() }) { Icon(Icons.Rounded.Refresh, "Recarregar") }
            Text(if (sent > 0) "$sent enviados pro Telegram" else "mega.nz", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            IconButton({ help = true }) { Icon(Icons.Rounded.HelpOutline, "Como funciona") }
        }
        if (loading in 1..99) LinearProgressIndicator({ loading / 100f }, Modifier.fillMaxWidth().height(2.dp))
        if (status.isNotBlank()) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (prog >= 0f) LinearProgressIndicator({ prog }, Modifier.fillMaxWidth().padding(top = 6.dp).clip(CircleShape))
            }
        }
        androidx.compose.ui.viewinterop.AndroidView(factory = { MegaWeb.obtain(it) }, modifier = Modifier.fillMaxWidth().weight(1f))
        // Painel fixo embaixo: o atalho pra mandar tudo do MEGA pro Telegram.
        Row(
            Modifier.fillMaxWidth().background(if (session.isNotBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer)
                .clickable(enabled = session.isNotBlank()) { panel = true }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (session.isNotBlank()) Icons.Rounded.CloudSync else Icons.Rounded.Login, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (session.isNotBlank()) "Mandar do MEGA direto pro Telegram" else "Entre na sua conta do MEGA acima",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(if (session.isNotBlank()) "Conectado: $session • não ocupa o celular" else "Depois aparece aqui o botão pra mandar tudo pro Telegram.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (session.isNotBlank()) Button({ panel = true }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Abrir") }
        }
    }
    if (panel) ModalBottomSheet({ panel = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxWidth().heightIn(max = 640.dp)) { MegaAccount { panel = false; MegaWeb.session.value = "" } }
    }
    if (help) AlertDialog({ help = false },
        title = { Text("Como trazer do MEGA") },
        text = { Text("1. Entre na sua conta do MEGA aqui (é o próprio site deles).\n" +
            "2. Toque em \"Abrir\" no painel de baixo e em \"Mandar tudo pro Telegram\" (ou escolha as pastas).\n" +
            "3. O app baixa um arquivo por vez, sobe pro Telegram e apaga do celular na hora. Roda em segundo plano, com a tela desligada.\n" +
            "4. Se tocar em Baixar dentro do site, aquele arquivo também vai direto pro Telegram.\n" +
            "5. Quando estiver tudo no Telegram, use \"Tirar do MEGA\" no painel (vai pra lixeira do MEGA).\n\n" +
            "Conta grátis do MEGA tem limite de download a cada algumas horas: o app espera e continua sozinho.") },
        confirmButton = { TextButton({ help = false }) { Text("Entendi") } })
}

@Composable
private fun MegaLogin(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(MegaStore.email) }
    var pass by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var needPin by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        GlassCard(padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CloudDownload, null, tint = Nv.Rose) }
                Spacer(Modifier.width(10.dp))
                Text("Trazer do MEGA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Text("Entre na sua conta do MEGA. O app baixa tudo de lá e salva na sua Nuvem do Telegram, nas mesmas pastas. Depois você pode tirar do MEGA com um toque.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("E-mail do MEGA") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Senha") }, singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } })
            if (needPin) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pin, { pin = it.filter { c -> c.isDigit() }.take(6) }, Modifier.fillMaxWidth(), label = { Text("Código de 6 dígitos (2 etapas)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            }
            err?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Nv.Rose, style = MaterialTheme.typography.bodySmall) }
            if (busy && status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, color = Nv.Gold, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(12.dp))
            Button({
                busy = true; err = null
                scope.launch {
                    try {
                        val c = MegaClient()
                        c.onStatus = { st -> scope.launch { status = st } }
                        withContext(Dispatchers.IO) { c.login(email, pass, pin.takeIf { needPin }) }
                        MegaStore.save(c, email.trim())
                        pass = ""
                        onDone()
                    } catch (e: MegaException) {
                        if (e.code == -26) needPin = true
                        err = e.message
                    } catch (e: Exception) {
                        err = "Não deu pra falar com o MEGA. Confira a internet. (${e.message ?: e.javaClass.simpleName})"
                    }
                    busy = false
                }
            }, Modifier.fillMaxWidth(), enabled = !busy && email.contains("@") && pass.isNotBlank() && (!needPin || pin.length == 6)) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Entrando… (pode levar uns segundos)") }
                else Text("Entrar no MEGA")
            }
            Spacer(Modifier.height(6.dp))
            Text("A senha não fica guardada no celular, só a sessão.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MegaAccount(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val nodes by MegaStore.nodes.collectAsStateWithLifecycle()
    val wm = remember { WorkManager.getInstance(ctx) }
    val w1 by wm.getWorkInfosForUniqueWorkFlow(MegaImport.WORK).collectAsStateWithLifecycle(emptyList())
    val w2 by wm.getWorkInfosForUniqueWorkFlow(MegaImport.WORK + "_later").collectAsStateWithLifecycle(emptyList())
    val ver by Db.version.collectAsStateWithLifecycle()
    val running = (w1 + w2).firstOrNull { it.state == WorkInfo.State.RUNNING }
    val waiting = (w1 + w2).any { it.state == WorkInfo.State.ENQUEUED }
    val lastSummary = (w1 + w2).filter { it.state == WorkInfo.State.SUCCEEDED }.mapNotNull { it.outputData.getString("summary") }.lastOrNull()
    var loadErr by remember { mutableStateOf<String?>(null) }
    var confirmTrash by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf(false) }
    var trashing by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (MegaStore.nodes.value == null) try {
            MegaStore.nodes.value = withContext(Dispatchers.IO) { MegaStore.client().fetchNodes() }
        } catch (e: Exception) {
            loadErr = e.message
            if (e is MegaException && e.code == -15) { MegaStore.logout(); onLogout() }
        }
    }
    val all = nodes
    val files = remember(all) { all?.let { MegaStore.files(it) }.orEmpty() }
    val done = remember(all, running?.progress, ver) { MegaStore.done() }
    // Só sai do MEGA o que já tem o mesmo arquivo (hash) registrado na Nuvem.
    val safeToTrash = remember(done, files, ver) {
        files.filter { (n, _) -> done[n.handle]?.let { h -> h.isNotBlank() && Db.anyByHash(h) != null } == true }.map { it.first }
    }
    val totalBytes = files.sumOf { it.first.size }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            GlassCard(padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AccountCircle, null, tint = Nv.Rose)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("MEGA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(MegaStore.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ confirmOut = true }) { Text("Sair") }
                }
                Spacer(Modifier.height(10.dp))
                when {
                    all == null && loadErr == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Lendo seus arquivos do MEGA…")
                    }
                    loadErr != null && all == null -> Text(loadErr ?: "", color = Nv.Rose)
                    else -> {
                        Text("${files.size} arquivos no MEGA • ${fmtBytes(totalBytes)}", style = MaterialTheme.typography.titleSmall)
                        Text("Já na Nuvem: ${done.size} de ${files.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (files.isNotEmpty()) LinearProgressIndicator({ done.size / files.size.toFloat() }, Modifier.fillMaxWidth().padding(top = 8.dp).clip(CircleShape))
                    }
                }
            }
        }
        item {
            val topFolders = remember(files) {
                files.map { it.second }.filter { it.isNotBlank() }.map { it.substringBefore('/') }
                    .groupingBy { it }.eachCount().toSortedMap(String.CASE_INSENSITIVE_ORDER)
            }
            var choose by remember { mutableStateOf(false) }
            var chosen by remember { mutableStateOf(MegaStore.folders) }
            GlassCard(onClick = { choose = true }, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FolderOpen, null, tint = Nv.Gold)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Pastas do MEGA", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(if (chosen.isEmpty()) "Todas" else chosen.sorted().joinToString(", "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    TextButton({ choose = true }) { Text("Escolher") }
                }
            }
            if (choose) AlertDialog({ choose = false },
                title = { Text("Quais pastas mandar?") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        item {
                            Row(Modifier.fillMaxWidth().clickable { chosen = emptySet() }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(chosen.isEmpty(), { chosen = emptySet() }); Text("Todas")
                            }
                        }
                        topFolders.forEach { (name, n) ->
                            item {
                                val on = name in chosen
                                Row(Modifier.fillMaxWidth().clickable { chosen = if (on) chosen - name else chosen + name }, verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(on, { chosen = if (on) chosen - name else chosen + name })
                                    Text(name, Modifier.weight(1f), maxLines = 1)
                                    Text("$n", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                },
                confirmButton = { Button({ MegaStore.folders = chosen; choose = false }) { Text("Salvar") } },
                dismissButton = { TextButton({ chosen = MegaStore.folders; choose = false }) { Text("Cancelar") } })
        }
        item {
            GlassCard(padding = 16.dp) {
                Text("1. Mandar pro Telegram", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Um arquivo por vez: baixa do MEGA, sobe pro Telegram (álbum \"MEGA\", mesmas pastas) e apaga do celular na hora. Não enche o celular. Roda em segundo plano, com a tela desligada.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                if (running != null) {
                    val d = running.progress.getInt("done", 0); val t = running.progress.getInt("total", 0)
                    Text(running.progress.getString("name") ?: "Trabalhando…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator({ running.progress.getInt("pct", 0) / 100f }, Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(CircleShape))
                    if (t > 0) Text("$d de $t", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton({ MegaImport.stop(ctx); wm.cancelUniqueWork(MegaImport.WORK + "_later") }, Modifier.fillMaxWidth()) { Text("Pausar") }
                } else {
                    if (waiting) Text("Na fila: começa assim que tiver internet (ou depois do limite do MEGA).", style = MaterialTheme.typography.bodySmall, color = Nv.Gold)
                    lastSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    MegaStore.lastError.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Nv.Rose) }
                    Spacer(Modifier.height(6.dp))
                    Button({ MegaImport.start(ctx) }, Modifier.fillMaxWidth(), enabled = all != null && done.size < files.size) {
                        Icon(Icons.Rounded.CloudDownload, null); Spacer(Modifier.width(8.dp))
                        Text(if (done.isEmpty()) "Mandar tudo pro Telegram" else "Continuar (${files.size - done.size} faltando)")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Conta grátis do MEGA tem limite de download por algumas horas. Se bater o limite, o app espera e continua sozinho.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            GlassCard(padding = 16.dp) {
                Text("2. Tirar do MEGA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Só sai do MEGA o que já está salvo na Nuvem (mesmo arquivo). Vai pra lixeira do MEGA, e você esvazia lá quando quiser.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                trashing?.let { Text(it, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(6.dp)) }
                Button({ confirmTrash = true }, Modifier.fillMaxWidth(), enabled = safeToTrash.isNotEmpty() && trashing == null && running == null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Rounded.DeleteSweep, null); Spacer(Modifier.width(8.dp))
                    Text(if (safeToTrash.isEmpty()) "Nada pronto pra tirar ainda" else "Tirar ${safeToTrash.size} do MEGA (${fmtBytes(safeToTrash.sumOf { it.size })})")
                }
            }
        }
    }
    if (confirmTrash) AlertDialog({ confirmTrash = false },
        title = { Text("Tirar ${safeToTrash.size} arquivos do MEGA?") },
        text = { Text("Todos já estão salvos na sua Nuvem do Telegram. Eles vão pra lixeira do MEGA (dá pra recuperar até você esvaziar a lixeira lá).") },
        confirmButton = { Button({
            confirmTrash = false
            val list = safeToTrash
            val trash = all?.let { MegaStore.trashHandle(it) }
            if (trash == null) { msg = "Não achei a lixeira do MEGA."; return@Button }
            scope.launch {
                var ok = 0; var fail = 0
                val mega = MegaStore.client()
                for ((i, n) in list.withIndex()) {
                    trashing = "Tirando do MEGA ${i + 1} de ${list.size}…"
                    try { withContext(Dispatchers.IO) { mega.moveToTrash(n.handle, trash) }; ok++ } catch (_: Exception) { fail++ }
                }
                trashing = null
                msg = "$ok arquivos foram pra lixeira do MEGA." + if (fail > 0) " $fail não deu (tente de novo)." else ""
                try { MegaStore.nodes.value = withContext(Dispatchers.IO) { mega.fetchNodes() } } catch (_: Exception) {}
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Tirar do MEGA") } },
        dismissButton = { TextButton({ confirmTrash = false }) { Text("Cancelar") } })
    if (confirmOut) AlertDialog({ confirmOut = false },
        title = { Text("Sair do MEGA?") },
        text = { Text("O que já veio pra Nuvem continua aqui. Pra trazer mais, é só entrar de novo.") },
        confirmButton = { Button({ confirmOut = false; MegaImport.stop(ctx); MegaStore.logout(); MegaStore.nodes.value = null; onLogout() }) { Text("Sair") } },
        dismissButton = { TextButton({ confirmOut = false }) { Text("Cancelar") } })
    msg?.let { AlertDialog({ msg = null }, confirmButton = { TextButton({ msg = null }) { Text("OK") } }, text = { Text(it) }) }
}
