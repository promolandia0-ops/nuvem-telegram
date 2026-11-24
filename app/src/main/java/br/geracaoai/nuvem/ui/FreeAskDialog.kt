package br.geracaoai.nuvem.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import br.geracaoai.nuvem.data.LocalItem
import br.geracaoai.nuvem.data.Repo
import br.geracaoai.nuvem.data.SafeTrash
import br.geracaoai.nuvem.work.FreeAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Janela "Liberar X? OK / Cancelar". OK confere de novo no Telegram e manda para a lixeira do celular. */
@Composable
fun FreeAskDialog() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val req = FreeAsk.request.value
    var items by remember { mutableStateOf<List<LocalItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun close() { FreeAsk.request.value = false; items = emptyList(); loaded = false; busy = false }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        msg = if (r.resultCode == android.app.Activity.RESULT_OK)
            "Pronto! Os arquivos foram para a lixeira do celular (dá pra recuperar por cerca de 30 dias). Tudo continua na sua nuvem."
        else "Nada foi apagado."
        close()
    }

    LaunchedEffect(req) {
        if (req && !loaded) {
            items = withContext(Dispatchers.IO) { try { Repo.freeableQuick(ctx).items } catch (_: Exception) { emptyList() } }
            loaded = true
            if (items.isEmpty()) { msg = "Nada para liberar agora. Só sai do celular o que já foi conferido no Telegram."; close() }
        }
    }

    if (req && loaded && items.isNotEmpty() && !busy) {
        val bytes = items.sumOf { it.size }
        AlertDialog(
            onDismissRequest = { close() },
            title = { Text("Liberar ${fmtBytes(bytes)}?") },
            text = { Text("${items.size} arquivos já estão no Telegram. Só saem os que eu confiro agora (arquivo e tamanho). Vão para a lixeira do celular, dá pra recuperar por cerca de 30 dias. Nada some da nuvem.") },
            confirmButton = {
                TextButton({
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { try { Repo.verifyForDelete(items) { } } catch (_: Exception) { emptyList() } }
                        val sender = if (ok.isEmpty()) null else SafeTrash.intent(ctx, ok.map { it.uri })
                        if (sender == null) {
                            msg = if (ok.isEmpty()) "Nenhum arquivo passou na conferência. Nada foi apagado." else "Não foi possível abrir a lixeira do celular. Nada foi apagado."
                            close()
                        } else launcher.launch(IntentSenderRequest.Builder(sender).build())
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ close() }) { Text("Cancelar") } },
        )
    }
    msg?.let { m -> AlertDialog({ msg = null }, confirmButton = { TextButton({ msg = null }) { Text("OK") } }, text = { Text(m) }) }
}
