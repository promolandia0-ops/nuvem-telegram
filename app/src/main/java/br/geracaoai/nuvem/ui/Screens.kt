package br.geracaoai.nuvem.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.geracaoai.nuvem.data.DiagLog
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.tg.Tg
import br.geracaoai.nuvem.work.Engine
import br.geracaoai.nuvem.work.Sched
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Moldura comum das telas de login e configuração. */
@Composable
fun Frame(titulo: String, sub: String, conteudo: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Text("Nuvem", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(titulo, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        conteudo()
    }
}

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var id by remember { mutableStateOf(if (Prefs.apiId != 0) Prefs.apiId.toString() else "") }
    var hash by remember { mutableStateOf(Prefs.apiHash) }
    Frame("Configurar acesso", "O Telegram exige uma chave própria e gratuita para apps como este. Leva 2 minutos:") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "1. Toque em “Abrir my.telegram.org” abaixo.",
                    "2. Digite seu número com +55 e toque em Next.",
                    "3. O código chega no app do Telegram (não por SMS). Cole e entre.",
                    "4. Toque em “API development tools”.",
                    "5. Em App title coloque Nuvem; em Short name coloque nuvem123; Platform: Android.",
                    "6. Toque em Create application.",
                    "7. Copie o “App api_id” (número) e o “App api_hash” (texto longo) para os campos abaixo.",
                ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org/apps")))
        }) {
            Icon(Icons.Rounded.OpenInNew, null)
            Spacer(Modifier.width(8.dp))
            Text("Abrir my.telegram.org")
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = id,
            onValueChange = { id = it.filter(Char::isDigit) },
            label = { Text("api_id") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = hash,
            onValueChange = { hash = it.trim() },
            label = { Text("api_hash") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                Prefs.apiId = id.toIntOrNull() ?: 0
                Prefs.apiHash = hash
                Td.sendParams()
                onDone()
            },
            enabled = id.isNotBlank() && hash.length >= 20,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Continuar") }
    }
}

@Composable
fun LoginScreen(state: TdApi.AuthorizationState?, onChangeApi: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember(state) { mutableStateOf(if (state is TdApi.AuthorizationStateWaitPhoneNumber) "+55" else "") }
    var err by remember(state) { mutableStateOf<String?>(null) }
    var busy by remember(state) { mutableStateOf(false) }
    fun go(f: suspend () -> Unit) {
        busy = true
        err = null
        scope.launch {
            try {
                f()
            } catch (e: Exception) {
                err = traduz(e.message)
            }
            busy = false
        }
    }

    when (state) {
        is TdApi.AuthorizationStateWaitPhoneNumber -> Frame(
            "Entrar no Telegram",
            "Use o número da sua conta. Suas fotos ficam guardadas nela.",
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Número com DDD") },
                singleLine = true,
                isError = err != null,
                supportingText = { err?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val numero = input.filter { it.isDigit() || it == '+' }
                    go { Td.send(TdApi.SetAuthenticationPhoneNumber(numero, null)) }
                },
                enabled = !busy && input.length > 8,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Enviar código") }
            TextButton(onClick = {
                Prefs.apiId = 0
                Prefs.apiHash = ""
                onChangeApi()
                go { Td.send(TdApi.Close()) }
            }) { Text("Trocar api_id/api_hash") }
        }

        is TdApi.AuthorizationStateWaitCode -> Frame(
            "Digite o código",
            "Enviamos um código para o app do Telegram em outro aparelho (ou por SMS).",
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.filter(Char::isDigit) },
                label = { Text("Código") },
                singleLine = true,
                isError = err != null,
                supportingText = { err?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { val codigo = input; go { Td.send(TdApi.CheckAuthenticationCode(codigo)) } },
                enabled = !busy && input.length >= 5,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Entrar") }
        }

        is TdApi.AuthorizationStateWaitPassword -> Frame(
            "Verificação em duas etapas",
            "Digite sua senha do Telegram." + (state.passwordHint.takeIf { it.isNotBlank() }?.let { "\nDica: $it" } ?: ""),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Senha") },
                singleLine = true,
                isError = err != null,
                supportingText = { err?.let { Text(it) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { val senha = input; go { Td.send(TdApi.CheckAuthenticationPassword(senha)) } },
                enabled = !busy && input.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Entrar") }
        }

        is TdApi.AuthorizationStateWaitRegistration -> Frame(
            "Conta não encontrada",
            "Crie sua conta no app oficial do Telegram primeiro e depois volte aqui.",
        ) {}

        is TdApi.AuthorizationStateWaitEmailAddress -> Frame(
            "E-mail de verificação",
            "O Telegram pediu um e-mail nesta conta.",
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.trim() },
                label = { Text("E-mail") },
                singleLine = true,
                isError = err != null,
                supportingText = { err?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { val mail = input; go { Td.send(TdApi.SetAuthenticationEmailAddress(mail)) } },
                enabled = !busy && "@" in input,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Continuar") }
        }

        is TdApi.AuthorizationStateWaitEmailCode -> Frame(
            "Código do e-mail",
            "Enviamos um código para ${state.codeInfo?.emailAddressPattern ?: "seu e-mail"}.",
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.filter(Char::isDigit) },
                label = { Text("Código") },
                singleLine = true,
                isError = err != null,
                supportingText = { err?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val cod = input
                    go { Td.send(TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(cod))) }
                },
                enabled = !busy && input.length >= 4,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Entrar") }
        }

        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(if (Prefs.lastError.isNotBlank()) traduz(Prefs.lastError) else "Conectando ao Telegram…")
                if (Prefs.lastError.contains("API_ID", true)) {
                    TextButton(onClick = {
                        Prefs.apiId = 0
                        Prefs.apiHash = ""
                        Prefs.lastError = ""
                        onChangeApi()
                    }) { Text("Corrigir api_id/api_hash") }
                }
            }
        }
    }
}

/** Painel principal: um botão grande para o backup de tudo, e o estado logo acima. */
@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Engine.state.collectAsState()
    var verDiag by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(Prefs.autoBackup) }
    var wifi by remember { mutableStateOf(Prefs.wifiOnly) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        perms.launch(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
        try { Tg.ensureDestination() } catch (_: Exception) {}
    }
    if (verDiag) {
        DiagScreen(onBack = { verDiag = false })
        return
    }
    val todosArquivos = try { Environment.isExternalStorageManager() } catch (_: Throwable) { true }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Nuvem", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Suas fotos e vídeos guardados no Telegram.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                StatRow("No aparelho", if (Prefs.lastLocal > 0) "${Prefs.lastLocal}" else "—")
                StatRow("Já na nuvem", if (Prefs.lastCloud > 0) "${Prefs.lastCloud}" else "—")
                StatRow("Faltam subir", if (Prefs.lastLocal > 0) "${Prefs.lastPending}" else "—")
                StatRow("Último backup", fmtData(Prefs.lastBackup))
            }
        }

        if (st.running) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (st.paused) "Pausado" else st.phase.ifBlank { "Trabalhando" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (st.total > 0) {
                        LinearProgressIndicator(
                            progress = { st.done.toFloat() / st.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${st.done} de ${st.total} enviados", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (st.current.isNotBlank()) {
                        Text(st.current, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (st.paused) {
                            Button(onClick = { Engine.resume() }) {
                                Icon(Icons.Rounded.PlayArrow, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Retomar")
                            }
                        } else {
                            OutlinedButton(onClick = { Engine.pause() }) {
                                Icon(Icons.Rounded.Pause, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Pausar")
                            }
                        }
                        OutlinedButton(onClick = { Sched.stop(ctx) }) {
                            Icon(Icons.Rounded.Stop, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Parar")
                        }
                    }
                }
            }
        } else {
            Button(onClick = { Sched.startNow(ctx) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Rounded.CloudUpload, null)
                Spacer(Modifier.width(8.dp))
                Text("Fazer backup de tudo", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (st.message.isNotBlank()) {
            Text(st.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Backup automático", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Sobe as fotos e vídeos novos sozinho, de tempos em tempos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = auto, onCheckedChange = {
                        auto = it
                        Prefs.autoBackup = it
                        Sched.keepPeriodic(ctx)
                    })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automático só com Wi‑Fi", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "O botão de fazer backup funciona sempre.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = wifi, onCheckedChange = {
                        wifi = it
                        Prefs.wifiOnly = it
                        Sched.keepPeriodic(ctx)
                    })
                }
            }
        }

        if (!todosArquivos) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Para achar também fotos e vídeos guardados fora da galeria (ex.: WhatsApp), " +
                            "ative o acesso a todos os arquivos.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
                        )
                    }) { Text("Abrir permissão de arquivos") }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { verDiag = true }) {
                Icon(Icons.Rounded.BugReport, null)
                Spacer(Modifier.width(6.dp))
                Text("Diagnóstico")
            }
            TextButton(onClick = {
                scope.launch {
                    try { Td.send(TdApi.LogOut(), 20_000) } catch (_: Exception) {}
                }
            }) { Text("Sair da conta") }
        }
    }
}

@Composable
fun StatRow(rotulo: String, valor: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(rotulo, style = MaterialTheme.typography.bodyLarge)
        Text(valor, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Tela de diagnóstico: o texto pode ser copiado e enviado quando algo der errado. */
@Composable
fun DiagScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st by Engine.state.collectAsState()
    var texto by remember { mutableStateOf("") }
    val atualizar = {
        val resumo = buildString {
            append("Backup rodando: ${st.running}\n")
            append("Pausado: ${st.paused}\n")
            append("Etapa: ${st.phase}\n")
            append("Progresso: ${st.done} de ${st.total}\n")
            append("Mensagem na tela: ${st.message}\n")
            append("Destino (chat): ${Prefs.chatId}\n")
            append("Automático: ${Prefs.autoBackup}, só Wi‑Fi: ${Prefs.wifiOnly}\n")
            append("Último backup: ${fmtData(Prefs.lastBackup)}\n")
            append("Último erro: ${Prefs.lastError.ifBlank { "nenhum" }}\n")
            append("Conta conectada: ${Td.ready}\n")
            append("Contagem: aparelho ${Prefs.lastLocal}, nuvem ${Prefs.lastCloud}, faltam ${Prefs.lastPending}\n")
        }
        texto = DiagLog.report(resumo)
    }
    LaunchedEffect(Unit) { atualizar() }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
            Text("Diagnóstico", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text(
            "Se algo der errado, toque em Copiar tudo e mande o texto.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Nuvem diagnóstico", texto))
                Toast.makeText(ctx, "Copiado", Toast.LENGTH_SHORT).show()
            }) { Text("Copiar tudo") }
            OutlinedButton(onClick = { atualizar() }) { Text("Atualizar") }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(texto, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun fmtData(ms: Long): String =
    if (ms <= 0L) "nunca" else SimpleDateFormat("dd/MM HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(ms))

fun traduz(m: String?): String {
    if (m.isNullOrBlank()) return "Erro desconhecido"
    return when {
        "PHONE_NUMBER_INVALID" in m -> "Número inválido. Use o formato +55 11 99999-9999."
        "PHONE_CODE_INVALID" in m -> "Código incorreto."
        "PHONE_CODE_EXPIRED" in m -> "O código expirou. Volte e peça outro."
        "PASSWORD_HASH_INVALID" in m -> "Senha incorreta."
        "API_ID" in m -> "api_id ou api_hash inválido."
        else -> m
    }
}
