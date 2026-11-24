package br.geracaoai.nuvem.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.tg.Td
import org.drinkless.tdlib.TdApi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NuvemTheme { Root() } }
    }
}

@Composable
fun NuvemTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) { content() }
    }
}

/** Escolhe a tela: primeiro a chave da API, depois o login, depois o painel. */
@Composable
fun Root() {
    val auth by Td.auth.collectAsState()
    var temApi by remember { mutableStateOf(Prefs.apiId != 0 && Prefs.apiHash.isNotBlank()) }
    when {
        !temApi -> SetupScreen(onDone = { temApi = true })
        auth is TdApi.AuthorizationStateReady -> AppShell()
        else -> LoginScreen(auth, onChangeApi = { temApi = false })
    }
}
