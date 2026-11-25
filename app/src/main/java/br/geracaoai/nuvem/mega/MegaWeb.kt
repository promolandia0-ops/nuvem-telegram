package br.geracaoai.nuvem.mega

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Base64
import android.view.ViewGroup
import android.webkit.*
import androidx.core.app.NotificationCompat
import androidx.work.*
import br.geracaoai.nuvem.R
import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.work.Backup
import br.geracaoai.nuvem.work.KeepAlive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Aba MEGA como navegador: o site do MEGA (mega.nz) roda aqui dentro e você entra normalmente.
 * - Com a sessão aberta no site, o app manda os arquivos do MEGA direto pro Telegram (MegaImport),
 *   um por vez, sem guardar nada no celular além do arquivo que está passando.
 * - Se você tocar em Baixar no site, o arquivo vai pra uma área temporária, sobe pro Telegram e é apagado.
 */
object MegaWeb {
    const val HOME = "https://mega.nz/login"

    val status = MutableStateFlow("")
    val sentCount = MutableStateFlow(0)
    val progress = MutableStateFlow(-1f)
    val loading = MutableStateFlow(0)
    val canBack = MutableStateFlow(false)
    /** E-mail da conta quando a sessão do site foi reconhecida. */
    val session = MutableStateFlow(if (MegaStore.loggedIn) MegaStore.email.ifBlank { "conectado" } else "")

    @SuppressLint("StaticFieldLeak")
    private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())

    fun tempDir(c: Context) = File(c.cacheDir, "mega_dl").apply { mkdirs() }

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(ctx: Context): WebView {
        web?.let { (it.parent as? ViewGroup)?.removeView(it); return it }
        val w = WebView(ctx)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(ctx.applicationContext), "NuvemSave")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(HOOK, null); canBack.value = view.canGoBack(); checkSession(view)
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { canBack.value = view.canGoBack(); checkSession(view) }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) { loading.value = newProgress }
        }
        w.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
            if (url.startsWith("blob:")) w.evaluateJavascript("window.__nuvemSave && window.__nuvemSave(${js(url)}, ${js(name)});", null)
        }
        w.loadUrl(HOME)
        web = w
        // O site do MEGA é uma página só: confere a sessão de tempos em tempos.
        val tick = object : Runnable { override fun run() { web?.let { checkSession(it) }; main.postDelayed(this, 4000) } }
        main.postDelayed(tick, 3000)
        return w
    }

    fun goBack(): Boolean = web?.let { if (it.canGoBack()) { it.goBack(); true } else false } ?: false
    fun reload() { web?.reload() }
    fun home() { web?.loadUrl(HOME) }

    private fun js(s: String) = org.json.JSONObject.quote(s)

    /**
     * Lê a sessão que VOCÊ abriu no site (identificador da sessão e chave da conta, guardados pelo próprio
     * site nesta WebView). A senha nunca é lida. Com ela o app lista e baixa seus arquivos pelo MEGA.
     */
    private fun checkSession(view: WebView) {
        view.evaluateJavascript(SESSION_JS) { raw ->
            try {
                val txt = org.json.JSONArray("[$raw]").getString(0)
                val o = org.json.JSONObject(txt)
                val sid = o.optString("sid"); val k = o.optString("k")
                if (sid.isBlank() || k.isBlank()) return@evaluateJavascript
                val arr = org.json.JSONArray(k)
                if (arr.length() != 4) return@evaluateJavascript
                val key = java.nio.ByteBuffer.allocate(16).apply { for (i in 0 until 4) putInt(arr.getLong(i).toInt()) }.array()
                if (MegaStore.sid == sid && MegaStore.loggedIn) return@evaluateJavascript
                val c = MegaClient(sid, key, o.optString("h"))
                MegaStore.save(c, o.optString("e"))
                MegaStore.nodes.value = null
                session.value = o.optString("e").ifBlank { "conectado" }
            } catch (_: Exception) {}
        }
    }

    private val SESSION_JS = """
        (function(){ try {
          var st = function(n){ try { return localStorage.getItem(n) || sessionStorage.getItem(n) || ''; } catch(e) { return ''; } };
          var sid = (typeof u_sid !== 'undefined' && u_sid) ? u_sid : st('sid');
          var k = (typeof u_k !== 'undefined' && u_k) ? JSON.stringify(Array.from(u_k)) : st('k');
          var h = (typeof u_handle !== 'undefined' && u_handle) ? u_handle : st('handle');
          var e = (typeof u_attr !== 'undefined' && u_attr && u_attr.email) ? u_attr.email : '';
          return JSON.stringify({sid: sid || '', k: k || '', h: h || '', e: e});
        } catch(x) { return '{}'; } })()
    """.trimIndent()

    /** Gancho: o arquivo que o site entrega (já descriptografado) vai em pedaços pro app. */
    private val HOOK = """
        (function(){
          if (window.__nuvemHooked) return; window.__nuvemHooked = true;
          function rd(b){ return new Promise(function(res,rej){ var f=new FileReader(); f.onload=function(){ var r=f.result; res(r.substring(r.indexOf(',')+1)); }; f.onerror=rej; f.readAsDataURL(b); }); }
          function sleep(ms){ return new Promise(function(r){ setTimeout(r, ms); }); }
          window.__nuvemSave = async function(url, name){
            try {
              var blob = await (await fetch(url)).blob();
              while (NuvemSave.canStart(String(blob.size)) !== 'ok') { await sleep(3000); }
              var CH = 1024*1024;
              NuvemSave.start(name || 'arquivo', blob.type || '', String(blob.size));
              for (var off=0; off<blob.size; off+=CH){ NuvemSave.chunk(await rd(blob.slice(off, off+CH))); }
              NuvemSave.end();
            } catch(e){ NuvemSave.fail(String(e)); }
          };
          var orig = HTMLAnchorElement.prototype.click;
          HTMLAnchorElement.prototype.click = function(){
            if (this.href && this.href.indexOf('blob:')===0 && this.hasAttribute('download')) { window.__nuvemSave(this.href, this.getAttribute('download')); return; }
            return orig.apply(this, arguments);
          };
        })();
    """.trimIndent()

    /** Recebe os pedaços num arquivo temporário e manda subir pro Telegram (depois apaga). */
    private class Bridge(private val app: Context) {
        private var out: OutputStream? = null
        private var file: File? = null
        private var name = ""
        private var total = 0L
        private var written = 0L

        /** Segura o próximo download se a área temporária já estiver cheia (o Telegram ainda subindo). */
        @JavascriptInterface
        fun canStart(size: String): String {
            val need = size.toLongOrNull() ?: 0L
            val pending = tempDir(app).listFiles()?.sumOf { it.length() } ?: 0L
            val free = try { StatFs(app.cacheDir.path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
            if (pending > 0 && pending + need > 3L * 1024 * 1024 * 1024) { status.value = "Esperando o Telegram subir o anterior pra baixar o próximo…"; return "wait" }
            if (free < need + 500L * 1024 * 1024) { status.value = "Pouco espaço livre: esperando subir o anterior…"; return "wait" }
            return "ok"
        }

        @JavascriptInterface
        fun start(fileName: String, mime: String, size: String) {
            closeQuietly(delete = true)
            name = fileName.replace('/', '_').ifBlank { "arquivo" }
            total = size.toLongOrNull() ?: 0L
            written = 0L
            val f = File(tempDir(app), System.currentTimeMillis().toString() + "_" + name)
            file = f
            out = try { FileOutputStream(f) } catch (e: Exception) { status.value = "Não deu pra preparar $name: ${e.message}"; null }
            progress.value = 0f
            status.value = "Recebendo $name do MEGA…"
        }

        @JavascriptInterface
        fun chunk(b64: String) {
            val o = out ?: return
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                o.write(bytes); written += bytes.size
                val p = if (total > 0) written.toFloat() / total else 0f
                progress.value = p
                status.value = "Recebendo $name do MEGA • ${(p * 100).toInt()}%"
            } catch (e: Exception) { status.value = "Falhou: $name (${e.message})"; closeQuietly(delete = true) }
        }

        @JavascriptInterface
        fun end() {
            val f = file ?: return
            try { out?.close() } catch (_: Exception) {}
            out = null; file = null; progress.value = -1f
            status.value = "✓ $name recebido. Subindo pro Telegram (some do celular depois)…"
            MegaUpload.enqueue(app, f, name)
        }

        @JavascriptInterface
        fun fail(msg: String) { status.value = "O download de $name falhou: $msg"; closeQuietly(delete = true) }

        private fun closeQuietly(delete: Boolean) {
            try { out?.close() } catch (_: Exception) {}
            out = null
            if (delete) file?.delete()
            file = null; progress.value = -1f
        }
    }
}

/** Fila de arquivos recebidos pelo navegador: sobe um por vez pro Telegram e apaga o temporário. */
object MegaUpload {
    const val WORK = "mega_upload"
    fun enqueue(c: Context, f: File, name: String) {
        val req = OneTimeWorkRequestBuilder<MegaUploadWorker>()
            .setInputData(workDataOf("path" to f.absolutePath, "name" to name))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, java.util.concurrent.TimeUnit.MINUTES).build()
        WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}

class MegaUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val keep = KeepAlive(ctx)
    private fun info(text: String, pct: Int) = ForegroundInfo(Backup.NOTIF_ID + 2,
        NotificationCompat.Builder(applicationContext, Backup.CHANNEL).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("MEGA → Telegram").setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setProgress(100, pct.coerceIn(0, 100), pct < 0).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    override suspend fun getForegroundInfo() = info("Preparando…", -1)

    override suspend fun doWork(): Result {
        val path = inputData.getString("path") ?: return Result.success()
        val name = inputData.getString("name") ?: File(path).name
        val f = File(path)
        if (!f.isFile) return Result.success()
        Backup.ensureChannel(applicationContext)
        try { setForeground(info("Subindo $name", 0)) } catch (_: Exception) {}
        keep.acquire()
        Td.init(applicationContext)
        return try {
            if (!Td.awaitReady()) return Result.retry()
            val dest = Repo.ensureDestination()
            val album = "MEGA"
            val mime = FileScan.mimeOf(name)
            val now = System.currentTimeMillis()
            val item = LocalItem(Uri.fromFile(f), f.absolutePath, name, f.length(), now, now, album, "MEGA/", mime)
            val hash = withContext(Dispatchers.IO) { Hash.quick(f) }
            if (!Repo.alreadyOnCloud(hash, item.size, name)) {
                val topic = Topics.idFor(dest.id, album)
                Repo.uploadFile(f.absolutePath, item.size, Repo.captionFor(item, album), null, { p ->
                    val pct = (p * 100).toInt()
                    MegaWeb.status.value = "Subindo $name pro Telegram • $pct%"
                    try { setForegroundAsync(info("Subindo $name • $pct%", pct)) } catch (_: Exception) {}
                }, mime, applicationContext.cacheDir, meta = Repo.metaOf(item, hash), chatId = dest.id, topicId = topic)
            }
            f.delete()
            MegaWeb.sentCount.value = MegaWeb.sentCount.value + 1
            MegaWeb.status.value = "✓ $name está no Telegram (e não ocupa o celular)."
            Result.success()
        } catch (e: java.util.concurrent.CancellationException) { throw e
        } catch (e: Exception) {
            MegaWeb.status.value = "Falhou ao subir $name: ${e.message}. Tento de novo."
            Result.retry()
        } finally { keep.release() }
    }
}
