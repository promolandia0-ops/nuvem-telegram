package br.geracaoai.nuvem.ui

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

/** Aba MEGA: entrar com e-mail e senha, trazer tudo para a Nuvem e depois tirar do MEGA. */
@Composable
fun MegaPane() {
    var logged by remember { mutableStateOf(MegaStore.loggedIn) }
    if (!logged) MegaLogin { logged = true } else MegaAccount { logged = false }
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
            GlassCard(padding = 16.dp) {
                Text("1. Trazer tudo pra Nuvem", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Baixa um arquivo por vez e manda pro seu Telegram, no álbum \"MEGA\" com as mesmas pastas. Roda em segundo plano, com a tela desligada.",
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
                        Text(if (done.isEmpty()) "Trazer tudo do MEGA" else "Continuar (${files.size - done.size} faltando)")
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
