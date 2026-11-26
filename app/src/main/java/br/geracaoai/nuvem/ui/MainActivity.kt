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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
        setContent { NuvemTheme { Root() } }
        if (intent?.getBooleanExtra("freeAsk", false) == true) br.geracaoai.nuvem.work.FreeAsk.request.value = true
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("freeAsk", false)) br.geracaoai.nuvem.work.FreeAsk.request.value = true
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
    var filesTab by rememberSaveable { mutableIntStateOf(0) }
    var browseFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var overlay by rememberSaveable { mutableStateOf<String?>(null) } // backup | advanced | settings | watched
    var viewer by remember { mutableStateOf<Pair<List<CloudItem>, Int>?>(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        perms.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
        try { Repo.ensureDestination(); Repo.reconcileCloud(); Repo.syncIndex() } catch (_: Exception) {}
    }
    val openBackup = { overlay = "backup" }
    val openSettings = { overlay = "settings" }
    val openViewer: (List<CloudItem>, Int) -> Unit = { l, i -> viewer = l to i }
    // Atualização: confere 1x por dia ao abrir e oferece instalar com um toque.
    var updFechado by rememberSaveable { mutableStateOf(false) }
    val updNova by Atualizar.disponivel.collectAsState()
    LaunchedEffect(Unit) { Atualizar.conferir() }
    if (updNova != null && !updFechado) AtualizarDialog { updFechado = true }
    BackHandler(viewer != null) { viewer = null }
    BackHandler(viewer == null && browseFolder != null) { browseFolder = null }
    BackHandler(viewer == null && browseFolder == null && overlay != null) {
        overlay = when (overlay) { "advanced" -> "backup"; else -> null }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.statusBars,
            bottomBar = { NvNavBar(tab, { n -> tab = n }) },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                AnimatedContent(tab, Modifier.fillMaxSize(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                    val acts: @Composable RowScope.() -> Unit = { TabHeaderActions(openBackup, openSettings) }
                    when (t) {
                        0 -> GalleryScreen(null, mode = 0, onBrowseFolder = { browseFolder = it }, actions = acts, banner = { BackupBanner(openBackup) }, onOpen = openViewer)
                        1 -> GalleryScreen(null, mode = 1, onBrowseFolder = { browseFolder = it }, actions = acts, banner = { BackupBanner(openBackup) }, onOpen = openViewer)
                        2 -> AlbumsTab(acts, banner = { BackupBanner(openBackup) }, onBrowseFolder = { browseFolder = it }, onOpen = openViewer)
                        else -> FilesScreen(acts, openViewer, banner = { BackupBanner(openBackup) }, onSpace = { overlay = "space" })
                    }
                }
            }
        }
        when (overlay) {
            "backup" -> BackupCenterScreen(onClose = { overlay = null }, onAddFolder = { overlay = null; tab = 3 }, onAdvanced = { overlay = "advanced" }, onSpace = { overlay = "space" })
            "space" -> SpaceScreen(onBack = { overlay = null }, onApps = { overlay = "apps" })
            "apps" -> AppsScreen(onBack = { overlay = "space" })
            "advanced" -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ overlay = "backup" }) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
                        Text("Mais opções de backup", style = MaterialTheme.typography.titleLarge)
                    }
                    BackupScreen(
                        onFotos = { _, v -> overlay = null; tab = if (v) 1 else 2 },
                        onBrowseFolder = { browseFolder = it },
                        onSettings = { overlay = "settings" },
                        onAssistidos = { overlay = "watched" },
                    )
                }
            }
            "settings" -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ overlay = null }) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
                    }
                    SettingsScreen(onLogout, onBrowseFolder = { browseFolder = it }, onBackup = { overlay = "backup" }, onAssistidos = { overlay = "watched" }, onSpace = { overlay = "space" }, onApps = { overlay = "apps" })
                }
            }
            "watched" -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ overlay = "settings" }) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
                    }
                    WatchedScreen()
                }
            }
        }
        if (browseFolder != null && viewer == null) {
            FolderBrowseScreen(browseFolder!!, onBack = { browseFolder = null }) { l, i -> viewer = l to i }
        }
        AnimatedVisibility(viewer != null, enter = fadeIn() + scaleIn(initialScale = 0.92f), exit = fadeOut() + scaleOut(targetScale = 0.92f)) {
            viewer?.let { (l, i) -> ViewerScreen(l, i) { viewer = null } }
        }
    }
}
