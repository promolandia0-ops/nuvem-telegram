package br.geracaoai.nuvem.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Acesso às pastas do Telegram pelo seletor de pastas (SAF).
 * Abre já apontando para a pasta (EXTRA_INITIAL_URI). Em Android 13+ o sistema bloqueia
 * Android/data, então o primeiro alvo é Android/media/<pacote>/Telegram, que o seletor consegue abrir.
 */
object SafAccess {
    const val AUTH = "com.android.externalstorage.documents"
    private const val ZW = "\u200B"

    fun installedPkgs(c: Context? = null): List<String> {
        if (c == null) return listOf(Watched.PKGS.first())
        val found = Watched.PKGS.filter { pkg ->
            try { c.packageManager.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
            catch (_: Exception) { false }
        }
        return found.ifEmpty { listOf(Watched.PKGS.first()) }
    }

    /** IDs de documento (primary:...) na ordem de tentativa. */
    fun candidateIds(c: Context? = null): List<String> {
        val pkgs = installedPkgs(c)
        val ids = ArrayList<String>()
        for (pkg in pkgs) {
            ids += "primary:Android/media/$pkg/Telegram"
            ids += "primary:Android/media/$pkg"
        }
        ids += "primary:Telegram"
        ids += "primary:Movies/Telegram"
        ids += "primary:Download/Telegram"
        val pkg = pkgs.first()
        ids += "primary:A${ZW}ndroid/data/$pkg"
        ids += "primary:A${ZW}ndroid/data"
        ids += "primary:Android/data/$pkg"
        ids += "primary:Android/data"
        return ids.distinct()
    }

    fun candidates(c: Context? = null): List<Uri> = candidateIds(c).map { documentUri(it) }

    fun documentUri(docId: String): Uri = DocumentsContract.buildDocumentUri(AUTH, docId)

    fun treeHint(c: Context? = null): String {
        val pkg = installedPkgs(c).first()
        return "Android/media/$pkg/Telegram"
    }

    fun intent(attempt: Int, c: Context? = null): Intent {
        val ids = candidateIds(c)
        val initial = documentUri(ids[attempt % ids.size])
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
            .putExtra("android.provider.extra.SHOW_ADVANCED", true)
            .putExtra("android.content.extra.SHOW_ADVANCED", true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }

    private fun docId(u: Uri) = try { DocumentsContract.getTreeDocumentId(u) } catch (_: Exception) {
        try { DocumentsContract.getDocumentId(u) } catch (_: Exception) { u.toString() }
    }
    private fun norm(s: String) = s.replace(ZW, "").lowercase()

    /** A árvore aponta para uma pasta do Telegram (media, pública ou Android/data)? */
    fun isTelegramDocId(id: String): Boolean {
        val d = norm(id).removePrefix("content://$AUTH/tree/").substringAfter("document/").replace("%3a", ":").replace("%2f", "/")
        val path = when {
            d.startsWith("primary:") -> d.removePrefix("primary:")
            else -> d.substringAfter("primary:", d)
        }.trim('/')
        if (path.startsWith("android/data")) {
            val rest = path.removePrefix("android/data").trim('/')
            return rest.isEmpty() || Watched.PKGS.any { rest == it || rest.startsWith("$it/") }
        }
        if (path.startsWith("android/media")) {
            val rest = path.removePrefix("android/media").trim('/')
            return Watched.PKGS.any { rest == it || rest.startsWith("$it/") }
        }
        if (path == "telegram" || path.startsWith("telegram/")) return true
        if (path.startsWith("movies/telegram") || path.startsWith("download/telegram")) return true
        return Watched.PKGS.any { path == it || path.startsWith("$it/") || path.contains("/$it") }
    }

    fun isTelegramTree(u: Uri): Boolean = isTelegramDocId(docId(u))

    /** Salva a permissão para sempre e confere se dá para ler. Retorna null se OK, ou a mensagem do problema. */
    fun accept(c: Context, u: Uri): String? {
        try { c.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch (_: Exception) { try { c.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
        if (!isTelegramTree(u)) return "A pasta escolhida não é a do Telegram (${docId(u).replace(ZW, "")}). Abra ${treeHint(c)} e toque em USAR ESTA PASTA."
        val n = try { c.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(u, docId(u)),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.count } ?: -1 } catch (_: Exception) { -1 }
        // Pasta recém-criada/vazia ainda é válida (pode não ter vídeos agora).
        if (n < 0) return "O Android não deixou ler essa pasta. Tente de novo e toque em USAR ESTA PASTA."
        Prefs2.watchedTree = u.toString()
        return null
    }

    /** Todas as árvores do Telegram já liberadas (permissões persistidas). */
    fun trees(c: Context): List<Uri> = c.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission && isTelegramTree(it.uri) }.map { it.uri }

    fun granted(c: Context) = trees(c).isNotEmpty() || Prefs2.watchedTree.isNotBlank() && run {
        try { isTelegramTree(Uri.parse(Prefs2.watchedTree)) } catch (_: Exception) { false }
    }
}
