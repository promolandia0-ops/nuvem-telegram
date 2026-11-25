package br.geracaoai.nuvem.mega

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.*
import br.geracaoai.nuvem.data.AllFilesAccess
import br.geracaoai.nuvem.data.FileScan
import br.geracaoai.nuvem.data.LocalMedia
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.work.Backup
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.OutputStream

/**
 * Aba MEGA como navegador: o site do MEGA (mega.nz) roda aqui dentro, você entra normalmente
 * e baixa o que quiser. O MEGA entrega o arquivo já descriptografado num "blob" da página;
 * o app lê esse blob em pedaços e grava em Download/MEGA. Depois o backup sobe pra Nuvem.
 */
object MegaWeb {
    const val HOME = "https://mega.nz/login"
    const val FOLDER_REL = "Download/MEGA/"

    /** Linha de status para a tela ("Salvando video.mp4 • 45%"). */
    val status = MutableStateFlow("")
    val savedCount = MutableStateFlow(0)
    val progress = MutableStateFlow(-1f)
    val pageTitle = MutableStateFlow("")
    val loading = MutableStateFlow(0)
    val canBack = MutableStateFlow(false)

    @SuppressLint("StaticFieldLeak")
    private var web: WebView? = null

    fun megaFolder(): File = File(LocalMedia.storageRoots().firstOrNull() ?: File("/storage/emulated/0"), FOLDER_REL)

    /** Mesma WebView entre trocas de aba: o download não para se você mudar de aba. */
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
            override fun onPageFinished(view: WebView, url: String?) { view.evaluateJavascript(HOOK, null); canBack.value = view.canGoBack() }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { canBack.value = view.canGoBack() }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) { loading.value = newProgress }
            override fun onReceivedTitle(view: WebView?, title: String?) { pageTitle.value = title ?: "" }
        }
        w.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
            if (url.startsWith("blob:")) w.evaluateJavascript("window.__nuvemSave && window.__nuvemSave(${js(url)}, ${js(name)});", null)
            else status.value = "Esse link não é de arquivo do MEGA; abra o arquivo pelo MEGA e toque em Baixar."
        }
        w.loadUrl(HOME)
        web = w
        return w
    }

    fun goBack(): Boolean = web?.let { if (it.canGoBack()) { it.goBack(); true } else false } ?: false
    fun reload() { web?.reload() }
    fun home() { web?.loadUrl(HOME) }

    private fun js(s: String) = org.json.JSONObject.quote(s)

    /**
     * Gancho na página: quando o MEGA manda salvar um blob (link com "download"), o app
     * lê o blob em pedaços de 1 MB e manda pro Android. Nada é enviado pra fora do celular.
     */
    private val HOOK = """
        (function(){
          if (window.__nuvemHooked) return; window.__nuvemHooked = true;
          function rd(b){ return new Promise(function(res,rej){ var f=new FileReader(); f.onload=function(){ var r=f.result; res(r.substring(r.indexOf(',')+1)); }; f.onerror=rej; f.readAsDataURL(b); }); }
          window.__nuvemSave = async function(url, name){
            try {
              var blob = await (await fetch(url)).blob();
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

    /** Recebe os pedaços do arquivo e grava em Download/MEGA (pela MediaStore, sem pedir permissão). */
    private class Bridge(private val app: Context) {
        private var out: OutputStream? = null
        private var uri: Uri? = null
        private var name = ""
        private var total = 0L
        private var written = 0L
        private val main = Handler(Looper.getMainLooper())
        private val kick = Runnable { onBatchDone() }

        @JavascriptInterface
        fun start(fileName: String, mime: String, size: String) {
            closeQuietly(delete = true)
            name = fileName.replace('/', '_').ifBlank { "arquivo" }
            total = size.toLongOrNull() ?: 0L
            written = 0L
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { FileScan.mimeOf(name) })
                    put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER_REL)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = app.contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)
                out = uri?.let { app.contentResolver.openOutputStream(it) }
                status.value = if (out == null) "Não deu pra criar o arquivo em Download/MEGA." else "Salvando $name…"
                progress.value = 0f
            } catch (e: Exception) { status.value = "Não deu pra salvar $name: ${e.message}" }
        }

        @JavascriptInterface
        fun chunk(b64: String) {
            val o = out ?: return
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                o.write(bytes); written += bytes.size
                val p = if (total > 0) written.toFloat() / total else 0f
                progress.value = p
                status.value = "Salvando $name • ${(p * 100).toInt()}%"
            } catch (e: Exception) { status.value = "Falhou ao salvar $name: ${e.message}"; closeQuietly(delete = true) }
        }

        @JavascriptInterface
        fun end() {
            val u = uri ?: return
            try {
                out?.close(); out = null
                app.contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                savedCount.value = savedCount.value + 1
                status.value = "✓ $name salvo em Download/MEGA. Vai subir pra sua Nuvem."
                progress.value = -1f
                uri = null
                // Junta vários downloads seguidos num backup só.
                main.removeCallbacks(kick); main.postDelayed(kick, 15_000)
            } catch (e: Exception) { status.value = "Falhou ao terminar $name: ${e.message}"; closeQuietly(delete = true) }
        }

        @JavascriptInterface
        fun fail(msg: String) { status.value = "O download de $name falhou: $msg"; closeQuietly(delete = true) }

        private fun closeQuietly(delete: Boolean) {
            try { out?.close() } catch (_: Exception) {}
            out = null
            if (delete) uri?.let { try { app.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
            uri = null; progress.value = -1f
        }

        /** Garante que Download/MEGA entra no backup e começa a subir. */
        private fun onBatchDone() {
            val dir = megaFolder().absolutePath.trimEnd('/')
            if (AllFilesAccess.granted() && !FileScan.isCovered(dir)) Prefs.fileFolders = Prefs.fileFolders + dir
            Thread { try { Backup.runNow(app, true) } catch (_: Exception) {} }.start()
        }
    }
}
