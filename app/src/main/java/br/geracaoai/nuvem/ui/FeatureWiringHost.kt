package br.geracaoai.nuvem.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import br.geracaoai.nuvem.data.CloudItem
import br.geracaoai.nuvem.data.FolderItemKind
import br.geracaoai.nuvem.data.FolderMediaItem
import br.geracaoai.nuvem.data.LocalItem

/**
 * Host só para testes de fiação: os atalhos e itens de Ajustes abrem o mesmo destino
 * que o app real (título da tela / confirmação).
 */
@Composable
fun FeatureWiringHost() {
    var pane by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf<String?>(null) }
    val folders = remember { sampleLiberarFolders() }
    val cameraItems = remember { sampleCameraFolderItems() }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        if (!(pane == "liberar" && folder != null)) {
            HomeShortcutRow(
                onFotos = { pane = "fotos" },
                onVideos = { pane = "videos" },
                onRestore = { pane = "restaurar" },
                onFree = { pane = "liberar"; folder = null },
                onDups = { pane = "duplicadas" },
                onSettings = { pane = "ajustes" },
            )
        }
        if (pane == "liberar" && folder != null) {
            FolderBrowseBody(
                folder = folder!!,
                items = if (folder == "Camera") cameraItems else emptyList(),
                loading = false,
                running = false,
                onBack = { folder = null },
                onOpenCloud = { _, _ -> },
                onVerify = { it },
                onTrashed = {},
                verifyInBody = false,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(FeatureTags.PANE_PASTA_LIBERAR),
            )
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (pane) {
                    "fotos" -> Text("Fotos", Modifier.testTag(FeatureTags.PANE_FOTOS))
                    "videos" -> Text("Vídeos", Modifier.testTag(FeatureTags.PANE_VIDEOS))
                    "restaurar" -> Text("Restaurar álbum", Modifier.testTag(FeatureTags.PANE_RESTAURAR))
                    "liberar" -> FreeFoldersBody(
                        folders = folders,
                        loading = false,
                        onOpenFolder = { folder = it },
                        modifier = Modifier.fillMaxWidth().testTag(FeatureTags.PANE_LIBERAR),
                    )
                    "duplicadas" -> Text("Duplicadas", Modifier.testTag(FeatureTags.PANE_DUPLICADAS))
                    "ajustes" -> SettingsWiringPane { dest -> if (dest == "liberar") { pane = "liberar"; folder = null } }
                }
            }
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

internal fun sampleLiberarFolders() = listOf(
    FolderProgress("Camera", 3, 18L * 1024 * 1024, 2, 12L * 1024 * 1024, emptyList(), true, false),
    FolderProgress("DCIM", 5, 1024L * 1024 * 1024, 5, 800L * 1024 * 1024, emptyList(), true, false),
)

internal fun sampleCameraFolderItems(): List<FolderMediaItem> {
    fun loc(id: Long, name: String, size: Long) = LocalItem(
        Uri.parse("content://media/external/images/media/$id"),
        "/storage/emulated/0/DCIM/Camera/$name", name, size, 1_000L + id, 1_000L + id,
        "Camera", "DCIM/Camera/", "image/jpeg", id,
    )
    val a = loc(1, "a.jpg", 6L * 1024 * 1024)
    val b = loc(2, "b.jpg", 6L * 1024 * 1024)
    val c = loc(3, "c.jpg", 4L * 1024 * 1024)
    val cloudA = CloudItem(11, 1, "a.jpg", "image/jpeg", a.size, a.taken, "Camera", a.path, "hash-a", null)
    val cloudOnly = CloudItem(99, 1, "old.jpg", "image/jpeg", 2L * 1024 * 1024, 50, "Camera", "/storage/emulated/0/DCIM/Camera/old.jpg", "hash-old", null)
    return listOf(
        FolderMediaItem("local:1", "a.jpg", a.size, a.taken, "image/jpeg", 0, FolderItemKind.LOCAL_BACKED, a, cloudA),
        FolderMediaItem("local:2", "b.jpg", b.size, b.taken, "image/jpeg", 0, FolderItemKind.LOCAL_BACKED, b, null),
        FolderMediaItem("local:3", "c.jpg", c.size, c.taken, "image/jpeg", 0, FolderItemKind.LOCAL_PENDING, c, null),
        FolderMediaItem("cloud:1:99", "old.jpg", cloudOnly.size, cloudOnly.taken, "image/jpeg", 0, FolderItemKind.CLOUD_ONLY, null, cloudOnly),
    )
}
