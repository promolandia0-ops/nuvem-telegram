package br.geracaoai.nuvem.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import br.geracaoai.nuvem.BuildConfig
import br.geracaoai.nuvem.data.DiagLog
import br.geracaoai.nuvem.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualizar pelo próprio app: confere a versão publicada no GitHub, baixa o APK e abre a tela "Instalar" do Android.
 * O repositório do Nuvem é público, então não precisa de login.
 */
object Atualizar {
    private const val BASE = "https://github.com/promolandia0-ops/nuvem-telegram/releases/download/nuvem-backup-tudo/"
    data class Nova(val versionCode: Int, val build: Int, val notas: String)

    /** Versão nova encontrada (null = em dia / não conferiu). */
    val disponivel = MutableStateFlow<Nova?>(null)
    /** -1 parado; 0..1 baixando. */
    val progresso = MutableStateFlow(-1f)
    val erro = MutableStateFlow<String?>(null)

    private fun abrir(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(5) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 60_000
                setRequestProperty("User-Agent", "Nuvem/" + BuildConfig.VERSION_NAME)
            }
            val code = c.responseCode
            if (code in 300..399) { val loc = c.getHeaderField("Location") ?: throw IllegalStateException("redirecionamento sem destino"); c.disconnect(); u = URL(u, loc) }
            else return c
        }
        throw IllegalStateException("redirecionamentos demais")
    }

    /** Confere no GitHub se há versão mais nova que a instalada. [forcar] ignora o limite de 1x por dia. */
    suspend fun conferir(forcar: Boolean = false): Nova? = withContext(Dispatchers.IO) {
        val agora = System.currentTimeMillis()
        val ultima = Prefs.raw.getLong("upd_ultima", 0L)
        if (!forcar && agora - ultima < 20 * 3600_000L) return@withContext disponivel.value
        try {
            val c = abrir(BASE + "version.json")
            if (c.responseCode != 200) { c.disconnect(); return@withContext null }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            c.disconnect()
            Prefs.raw.edit().putLong("upd_ultima", agora).apply()
            val n = Nova(j.optInt("versionCode"), j.optInt("build"), j.optString("notas"))
            val nova = if (n.versionCode > BuildConfig.VERSION_CODE) n else null
            disponivel.value = nova
            nova
        } catch (e: Exception) { DiagLog.log("ATUALIZAR", "não conferiu a versão", e); null }
    }

    /** O Android deixa este app instalar APKs? (liberado uma vez nas configurações). */
    fun podeInstalar(c: Context) = c.packageManager.canRequestPackageInstalls()

    fun pedirPermissao(c: Context) {
        try {
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    /** Baixa o APK novo e abre a tela de instalar. */
    suspend fun baixarEInstalar(c: Context): Boolean {
        erro.value = null
        val f = withContext(Dispatchers.IO) {
            try {
                progresso.value = 0f
                val dest = File(c.cacheDir, "atualizacao.apk").apply { delete() }
                val conn = abrir(BASE + "nuvem-telegram.apk")
                if (conn.responseCode != 200) throw IllegalStateException("GitHub respondeu ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { i ->
                    dest.outputStream().use { o ->
                        val buf = ByteArray(256 * 1024); var lido = 0L
                        while (true) {
                            val n = i.read(buf); if (n < 0) break
                            o.write(buf, 0, n); lido += n
                            if (total > 0) progresso.value = (lido.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                }
                conn.disconnect()
                dest
            } catch (e: Exception) {
                DiagLog.log("ATUALIZAR", "não baixou", e); erro.value = "Não deu pra baixar: ${e.message}"; null
            } finally { progresso.value = -1f }
        } ?: return false
        return try {
            val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
            c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) { erro.value = "Não abriu o instalador: ${e.message}"; false }
    }
}

/** Janela "Versão nova disponível" (aparece sozinha ao abrir o app, 1x por dia, ou por Ajustes). */
@Composable
fun AtualizarDialog(onFechar: () -> Unit) {
    val ctx = LocalContext.current
    val nova by Atualizar.disponivel.collectAsState()
    val prog by Atualizar.progresso.collectAsState()
    val erro by Atualizar.erro.collectAsState()
    var precisaPermissao by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val n = nova ?: return
    AlertDialog({ if (prog < 0f) onFechar() },
        icon = { Icon(Icons.Rounded.SystemUpdate, null) },
        title = { Text("Versão nova do Nuvem") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Build ${n.build} (você tem a ${BuildConfig.VERSION_CODE - 16}).")
                if (n.notas.isNotBlank()) Text("Novidade: ${n.notas}", style = MaterialTheme.typography.bodySmall)
                if (prog >= 0f) {
                    Text("Baixando… ${(prog * 100).toInt()}%")
                    LinearProgressIndicator({ prog }, Modifier.fillMaxWidth())
                }
                if (precisaPermissao) Text("O Android pede pra liberar \"Instalar apps desconhecidos\" pro Nuvem. Ative na tela que abriu e toque em Atualizar de novo.",
                    style = MaterialTheme.typography.bodySmall, color = Nv.Gold)
                erro?.let { Text(it, color = Nv.Rose, style = MaterialTheme.typography.bodySmall) }
                Text("Instala por cima, sem perder login nem backup.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Button(enabled = prog < 0f, onClick = {
                if (!Atualizar.podeInstalar(ctx)) { precisaPermissao = true; Atualizar.pedirPermissao(ctx); return@Button }
                precisaPermissao = false
                scope.launch { if (Atualizar.baixarEInstalar(ctx)) onFechar() }
            }) { Text(if (prog >= 0f) "Baixando…" else "Atualizar") }
        },
        dismissButton = { TextButton(enabled = prog < 0f, onClick = onFechar) { Text("Depois") } })
}
