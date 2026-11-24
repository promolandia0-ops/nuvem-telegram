package br.geracaoai.nuvem.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import br.geracaoai.nuvem.tg.CloudRec
import br.geracaoai.nuvem.tg.Tg

/**
 * Casca do app depois do login: abas Fotos e Backup embaixo.
 * A visualização em tela cheia abre por cima de tudo.
 */
@Composable
fun AppShell() {
    var aba by rememberSaveable { mutableStateOf(0) }
    var aberto by remember { mutableStateOf<Pair<List<CloudRec>, Int>?>(null) }
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

    val visto = aberto
    if (visto != null) {
        ViewerScreen(lista = visto.first, inicio = visto.second, onClose = { aberto = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (aba == 0) {
                GalleryScreen(onOpen = { lista, i -> aberto = lista to i })
            } else {
                HomeScreen()
            }
        }
        NavigationBar {
            NavigationBarItem(
                selected = aba == 0,
                onClick = { aba = 0 },
                icon = { Icon(Icons.Rounded.PhotoLibrary, null) },
                label = { Text("Fotos") },
            )
            NavigationBarItem(
                selected = aba == 1,
                onClick = { aba = 1 },
                icon = { Icon(Icons.Rounded.CloudUpload, null) },
                label = { Text("Backup") },
            )
        }
    }
}
