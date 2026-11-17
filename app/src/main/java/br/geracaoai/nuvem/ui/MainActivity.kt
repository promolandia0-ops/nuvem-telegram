package br.geracaoai.nuvem.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import br.geracaoai.nuvem.data.CloudItem
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.data.Repo
import br.geracaoai.nuvem.play.PlayerHolder
import br.geracaoai.nuvem.tg.Td
import org.drinkless.tdlib.TdApi

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NuvemTheme { Surface { Root() } } }
    }

    fun aplicarOrientacao(codigo: Int) { requestedOrientation = codigo }

    fun brilho(v: Float) { window.attributes = window.attributes.apply { screenBrightness = v.coerceIn(0.01f, 1f) } }

    fun entrarPip(): Boolean {
        return try {
            if (PlayerHolder.player?.isPlaying != true) return false
            val vs = PlayerHolder.player?.videoSize
            val ratio = if (vs != null && vs.width > 0 && vs.height > 0) Rational(vs.width, vs.height) else Rational(16, 9)
            val params = PictureInPictureParams.Builder().setAspectRatio(ratio).build()
            enterPictureInPictureMode(params)
        } catch (_: Exception) { false }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PlayerHolder.shouldEnterPip && PlayerHolder.player?.isPlaying == true) entrarPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlayerHolder.keepAlive = isInPictureInPictureMode || PlayerHolder.backgroundAudio
    }
}

@Composable
fun Root() {
    val auth by Td.auth.collectAsState()
    var hasApi by remember { mutableStateOf(Prefs.apiId != 0) }
    var hasChat by remember { mutableStateOf(Prefs.chatId != 0L) }
    val stage = when {
        !hasApi -> 0
        auth !is TdApi.AuthorizationStateReady -> 1
        !hasChat -> 2
        else -> 3
    }
    AnimatedContent(stage, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "root") { s ->
        when (s) {
            0 -> SetupScreen { hasApi = true }
            1 -> { LaunchedEffect(auth) { if (Prefs.apiId == 0) hasApi = false }; LoginScreen(auth) }
            2 -> DestinationScreen { hasChat = true }
            else -> MainShell(onLogout = { hasChat = false; hasApi = Prefs.apiId != 0 })
        }
    }
}

@Composable
fun MainShell(onLogout: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var viewer by remember { mutableStateOf<Pair<List<CloudItem>, Int>?>(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        perms.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
        try { Repo.ensureDestination(); Repo.reconcileCloud(); Repo.syncIndex() } catch (_: Exception) {}
    }
    BackHandler(viewer != null) { viewer = null }
    Box {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf(Triple("Fotos", Icons.Rounded.Photo, 0), Triple("Álbuns", Icons.Rounded.PhotoLibrary, 1), Triple("Assistidos", Icons.Rounded.OndemandVideo, 4),
                    Triple("Backup", Icons.Rounded.CloudUpload, 2), Triple("Ajustes", Icons.Rounded.Settings, 3)).forEach { (l, i, n) ->
                    NavigationBarItem(tab == n, { tab = n }, { Icon(i, null) }, label = { Text(l) })
                }
            }
        }) { pad ->
            AnimatedContent(tab, Modifier.padding(pad), transitionSpec = { fadeIn() + scaleIn(initialScale = 0.98f) togetherWith fadeOut() }, label = "tab") { t ->
                when (t) {
                    0 -> GalleryScreen(null) { l, i -> viewer = l to i }
                    1 -> AlbumsScreen { l, i -> viewer = l to i }
                    2 -> BackupScreen()
                    4 -> WatchedScreen()
                    else -> SettingsScreen(onLogout)
                }
            }
        }
        AnimatedVisibility(viewer != null, enter = fadeIn() + scaleIn(initialScale = 0.92f), exit = fadeOut() + scaleOut(targetScale = 0.92f)) {
            viewer?.let { (l, i) -> ViewerScreen(l, i) { viewer = null } }
        }
    }
}
