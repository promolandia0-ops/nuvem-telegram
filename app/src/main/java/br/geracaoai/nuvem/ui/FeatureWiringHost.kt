package br.geracaoai.nuvem.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Host só para testes de fiação: os atalhos e itens de Ajustes abrem o mesmo destino
 * que o app real (título da tela / confirmação).
 */
@Composable
fun FeatureWiringHost() {
    var pane by remember { mutableStateOf<String?>(null) }
    var confirmTrash by remember { mutableStateOf(false) }
    Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
        HomeShortcutRow(
            onFotos = { pane = "fotos" },
            onVideos = { pane = "videos" },
            onRestore = { pane = "restaurar" },
            onFree = { pane = "liberar" },
            onDups = { pane = "duplicadas" },
            onSettings = { pane = "ajustes" },
        )
        when (pane) {
            "fotos" -> Text("Fotos", Modifier.testTag(FeatureTags.PANE_FOTOS))
            "videos" -> Text("Vídeos", Modifier.testTag(FeatureTags.PANE_VIDEOS))
            "restaurar" -> Text("Restaurar álbum", Modifier.testTag(FeatureTags.PANE_RESTAURAR))
            "liberar" -> FreeUpBody(
                checking = false, running = false, itemCount = 3, bytes = 12L * 1024 * 1024,
                onLiberar = { confirmTrash = true },
                modifier = Modifier.fillMaxWidth(),
            )
            "duplicadas" -> Text("Duplicadas", Modifier.testTag(FeatureTags.PANE_DUPLICADAS))
            "ajustes" -> SettingsWiringPane { dest -> if (dest == "liberar") pane = "liberar" }
        }
        if (pane == "liberar" && confirmTrash) {
            AlertDialog(
                onDismissRequest = { confirmTrash = false },
                title = { Text("Mover 3 itens para a lixeira?") },
                text = { Text("Vão para a lixeira do celular.") },
                confirmButton = { Button({ confirmTrash = false }) { Text("Mover para a lixeira") } },
                dismissButton = { TextButton({ confirmTrash = false }) { Text("Cancelar") } },
            )
        }
    }
}

@Composable
fun SettingsWiringPane(onOpen: (String) -> Unit) {
    var destConfirm by remember { mutableStateOf(false) }
    Column(Modifier.testTag(FeatureTags.PANE_AJUSTES)) {
        SettingsLine(Icons.Rounded.Cloud, "Destino", "Grupo", tag = FeatureTags.SETTINGS_DESTINO) { destConfirm = true; onOpen("destino") }
        SettingsLine(Icons.Rounded.Email, "E-mail de login", "Cadastrar", tag = FeatureTags.SETTINGS_EMAIL) { onOpen("email") }
        SettingsLine(Icons.Rounded.PhoneAndroid, "Sessão neste celular", "Salva aqui", tag = FeatureTags.SETTINGS_SESSAO)
        SettingsLine(Icons.Rounded.CleaningServices, "Limpar legendas antigas", "Hashtag curta", tag = FeatureTags.SETTINGS_LEGENDAS) { onOpen("legendas") }
        SettingsLine(Icons.Rounded.CleaningServices, "Liberar espaço", "Lixeira do celular", tag = FeatureTags.SETTINGS_LIBERAR) { onOpen("liberar") }
        SettingsLine(Icons.Rounded.Sync, "Reindexar nuvem", "Relê o destino", tag = FeatureTags.SETTINGS_REINDEXAR) { onOpen("reindexar") }
        SettingsLine(Icons.Rounded.SwapHoriz, "Trocar destino", "Grupo, canal ou Mensagens Salvas", tag = FeatureTags.SETTINGS_TROCAR_DESTINO) { destConfirm = true; onOpen("trocar") }
        SettingsLine(Icons.AutoMirrored.Rounded.Logout, "Sair / trocar de conta", "Outro número", tag = FeatureTags.SETTINGS_SAIR) { onOpen("sair") }
        Spacer(Modifier.height(8.dp))
    }
    if (destConfirm) {
        AlertDialog(
            onDismissRequest = { destConfirm = false },
            title = { Text("Trocar destino?") },
            text = { Text("Os arquivos que já foram enviados não serão reenviados, a menos que você marque a opção.") },
            confirmButton = { Button({ destConfirm = false }) { Text("Continuar") } },
            dismissButton = { TextButton({ destConfirm = false }) { Text("Cancelar") } },
        )
    }
}
