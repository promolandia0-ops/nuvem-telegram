package br.geracaoai.nuvem.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import br.geracaoai.nuvem.data.LocalItem
import br.geracaoai.nuvem.data.Repo
import br.geracaoai.nuvem.work.FreeAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Depois do backup: "Liberar X?" com a limpeza transparente (confere, mostra GB, apaga e mede). */
@Composable
fun FreeAskDialog() {
    val ctx = LocalContext.current
    val req = FreeAsk.request.value
    var items by remember { mutableStateOf<List<LocalItem>?>(null) }
    var empty by remember { mutableStateOf(false) }
    LaunchedEffect(req) {
        if (req && items == null) {
            val list = withContext(Dispatchers.IO) { try { Repo.freeableQuick(ctx).items } catch (_: Exception) { emptyList() } }
            if (list.isEmpty()) { empty = true; FreeAsk.request.value = false } else items = list
        }
    }
    CleanupFlow(if (req) items else null) { items = null; FreeAsk.request.value = false }
    if (empty) AlertDialog({ empty = false }, confirmButton = { TextButton({ empty = false }) { Text("OK") } },
        text = { Text("Nada pra liberar agora. Só sai do celular o que já está conferido no Telegram.") })
}
