package br.geracaoai.nuvem.ui

import android.app.Activity
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.geracaoai.nuvem.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Espaço livre no armazenamento do celular, em bytes. */
fun freeSpace(): Long = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (_: Exception) { 0L }

/**
 * Limpeza com transparência, igual em todo o app:
 * 1) confere no Telegram cada arquivo; 2) mostra quantos GB estão na nuvem e quantos NÃO estão (esses ficam);
 * 3) você escolhe apagar de vez (libera na hora) ou lixeira (só libera em ~30 dias);
 * 4) mostra o espaço livre antes e depois, medido.
 * Exibe as janelas quando [candidates] não é nulo. [onDone] fecha o fluxo.
 */
@Composable
fun CleanupFlow(candidates: List<LocalItem>?, onDone: (freedBytes: Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember(candidates) { mutableStateOf(if (candidates != null) "verify" else "") }
    var progress by remember(candidates) { mutableIntStateOf(0) }
    var safe by remember(candidates) { mutableStateOf<List<LocalItem>>(emptyList()) }
    var notSafe by remember(candidates) { mutableStateOf<List<LocalItem>>(emptyList()) }
    var freeBefore by remember(candidates) { mutableLongStateOf(0L) }
    var permanent by remember(candidates) { mutableStateOf(true) }
    var result by remember(candidates) { mutableStateOf<String?>(null) }
    var resultOk by remember(candidates) { mutableStateOf(true) }
    var freedFinal by remember(candidates) { mutableLongStateOf(0L) }

    fun finish(text: String, ok: Boolean, freed: Long) { result = text; resultOk = ok; freedFinal = freed; phase = "result" }

    suspend fun afterSystemDialog(accepted: Boolean) {
        if (!accepted) { finish("Nada foi apagado. Você cancelou na janela do Android.", false, 0); return }
        val files = safe.filter { it.uri.scheme == "file" }
        if (permanent) withContext(Dispatchers.IO) { files.forEach { try { File(it.path).delete() } catch (_: Exception) {} } }
        val gone = withContext(Dispatchers.IO) { safe.filter { !File(it.path).exists() } }
        val goneBytes = gone.sumOf { it.size }
        val after = freeSpace()
        val gained = (after - freeBefore).coerceAtLeast(0L)
        try { MediaScan.invalidate() } catch (_: Exception) {}
        ActionLog.add(if (permanent) "apagar" else "lixeira", "Limpeza: ${gone.size} arquivos (${fmtBytes(goneBytes)}) saíram do celular; todos conferidos no Telegram", gone.size, goneBytes)
        val sb = StringBuilder()
        if (permanent) {
            sb.append("Apagados do celular: ${gone.size} arquivos • ${fmtBytes(goneBytes)}\n\n")
            sb.append("Espaço livre: ${fmtBytes(freeBefore)} → ${fmtBytes(after)}")
            if (gained > 0) sb.append("  (+${fmtBytes(gained)})")
        } else {
            sb.append("Na lixeira do celular: ${gone.size} arquivos • ${fmtBytes(goneBytes)}\n\n")
            sb.append("O espaço só volta quando a lixeira esvaziar (Galeria → Lixeira → Esvaziar) ou sozinho em ~30 dias.")
        }
        sb.append("\n\n☁ Todos continuam salvos na sua nuvem do Telegram (abas Fotos, Vídeos e Arquivos).")
        val missing = safe.size - gone.size
        if (missing > 0) sb.append("\n\n$missing não saíram (o Android não deixou).")
        if (notSafe.isNotEmpty()) sb.append("\n\n${notSafe.size} arquivos (${fmtBytes(notSafe.sumOf { it.size })}) ficaram no celular porque NÃO estão na nuvem.")
        finish(sb.toString(), gone.isNotEmpty(), if (permanent) goneBytes else 0L)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        scope.launch { afterSystemDialog(r.resultCode == Activity.RESULT_OK) }
    }

    LaunchedEffect(candidates) {
        val list = candidates ?: return@LaunchedEffect
        freeBefore = freeSpace()
        val ok = withContext(Dispatchers.IO) { try { Repo.verifyForDelete(list) { progress = it } } catch (_: Exception) { emptyList() } }
        val okPaths = ok.map { it.path }.toHashSet()
        safe = ok
        notSafe = list.filter { it.path !in okPaths }
        phase = "confirm"
    }

    fun go() {
        val media = safe.filter { it.uri.scheme == "content" }.map { it.uri }
        val files = safe.filter { it.uri.scheme == "file" }
        if (!permanent && media.isEmpty()) { finish("Esses arquivos não são da galeria, então não dá pra mandar pra lixeira. Escolha \"Apagar de vez\".", false, 0); return }
        if (files.isNotEmpty() && permanent && !AllFilesAccess.granted()) {
            finish("Pra apagar arquivos fora da galeria, libere \"Acesso a todos os arquivos\" em Arquivos → Celular.", false, 0); return
        }
        if (media.isEmpty()) { phase = "working"; scope.launch { afterSystemDialog(true) }; return }
        val sender = try {
            if (permanent) MediaStore.createDeleteRequest(ctx.contentResolver, media).intentSender
            else MediaStore.createTrashRequest(ctx.contentResolver, media, true).intentSender
        } catch (_: Exception) { null }
        if (sender == null) { finish("O Android não abriu a janela de apagar. Nada foi apagado.", false, 0); return }
        phase = "working"
        launcher.launch(IntentSenderRequest.Builder(sender).build())
    }

    if (candidates == null) return
    when (phase) {
        "verify" -> AlertDialog({}, confirmButton = {}, title = { Text("Conferindo no Telegram…") }, text = {
            Column {
                Text("Vendo se cada arquivo já está salvo na nuvem (mesmo arquivo e tamanho): $progress de ${candidates.size}.")
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator({ progress / candidates.size.coerceAtLeast(1).toFloat() }, Modifier.fillMaxWidth())
            }
        })
        "confirm" -> {
            val safeBytes = safe.sumOf { it.size }
            AlertDialog({ onDone(0) },
                title = { Text(if (safe.isEmpty()) "Nada pra limpar" else "Liberar ${fmtBytes(safeBytes)}?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryLine(Icons.Rounded.CloudDone, Nv.Cyan, "${safe.size} arquivos • ${fmtBytes(safeBytes)}", "Estão salvos na nuvem (conferi agora). Podem sair do celular.")
                        if (notSafe.isNotEmpty()) SummaryLine(Icons.Rounded.CloudOff, Nv.Gold, "${notSafe.size} arquivos • ${fmtBytes(notSafe.sumOf { it.size })}",
                            "NÃO estão na nuvem. Ficam no celular (faça backup antes).")
                        if (safe.isNotEmpty()) {
                            Text("Espaço livre agora: ${fmtBytes(freeBefore)}", style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                            ChoiceRow(permanent, "Apagar de vez", "Libera ${fmtBytes(safeBytes)} na hora. A cópia fica na nuvem.") { permanent = true }
                            ChoiceRow(!permanent, "Mandar pra lixeira", "Dá pra desfazer, mas o espaço só volta em ~30 dias.") { permanent = false }
                        }
                    }
                },
                confirmButton = {
                    if (safe.isNotEmpty()) Button({ go() }, colors = ButtonDefaults.buttonColors(containerColor = if (permanent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
                        Text(if (permanent) "Apagar ${fmtBytes(safeBytes)}" else "Mandar pra lixeira")
                    }
                },
                dismissButton = { TextButton({ onDone(0) }) { Text(if (safe.isEmpty()) "OK" else "Cancelar") } },
            )
        }
        "working" -> AlertDialog({}, confirmButton = {}, text = {
            Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)); Text("Apagando e medindo o espaço…") }
        })
        "result" -> AlertDialog({ onDone(freedFinal) },
            icon = { Icon(if (resultOk) Icons.Rounded.CheckCircle else Icons.Rounded.Info, null, tint = if (resultOk) Nv.Cyan else Nv.Gold) },
            title = { Text(if (resultOk) "Pronto" else "Atenção") },
            text = { Text(result ?: "") },
            confirmButton = { TextButton({ onDone(freedFinal) }) { Text("OK") } })
    }
}

@Composable
private fun SummaryLine(icon: androidx.compose.ui.graphics.vector.ImageVector, tone: Color, title: String, sub: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tone.copy(alpha = 0.12f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tone)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(Modifier), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Liberar espaço do celular, igual em todo o app: procura fotos, vídeos e arquivos (modo Arquivos)
 * que já estão na nuvem e abre a limpeza transparente. Use: val free = rememberFreeSpace(); free()
 */
@Composable
fun rememberFreeSpace(): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<LocalItem>?>(null) }
    var empty by remember { mutableStateOf(false) }
    CleanupFlow(items) { _ -> items = null }
    if (loading) AlertDialog({}, confirmButton = {}, text = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
            Text("Procurando fotos, vídeos e arquivos que já estão na nuvem…")
        }
    })
    if (empty) AlertDialog({ empty = false }, confirmButton = { TextButton({ empty = false }) { Text("OK") } },
        title = { Text("Nada pra liberar agora") },
        text = { Text("Não achei no celular nenhum arquivo que já esteja confirmado na nuvem.\n\n" +
            "Se o backup ainda está rodando, espere terminar. Arquivos fora da galeria (PDF, ZIP…) precisam estar numa pasta com backup em Arquivos → Celular.") })
    return remember {
        {
            if (!loading) {
                loading = true
                scope.launch {
                    val list = withContext(Dispatchers.IO) { try { Repo.freeableQuick(ctx).items } catch (_: Exception) { emptyList() } }
                    loading = false
                    DiagLog.log("LIMPAR", "liberar espaço: ${list.size} candidatos (${list.count { it.uri.scheme == "file" }} arquivos fora da galeria)")
                    if (list.isEmpty()) empty = true else items = list
                }
            }
        }
    }
}
