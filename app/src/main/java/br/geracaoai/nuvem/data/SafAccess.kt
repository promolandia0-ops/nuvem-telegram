package br.geracaoai.nuvem.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Acesso à pasta oculta do Telegram (Android/data/...) SEM Shizuku, pelo seletor de pastas do Android (SAF).
 * Usa a mesma técnica dos gerenciadores de arquivos (MT, FV, File Explorer etc.):
 * abre o seletor já apontando para a pasta, e em Android 13+ usa o caminho com um caractere invisível
 * (U+200B) em "A​ndroid", que o seletor não reconhece como pasta protegida, mas o sistema resolve normalmente.
 */
object SafAccess {
    private const val AUTH = "com.android.externalstorage.documents"
    private const val ZW = "\u200B"

    /** Modos de tentativa, na ordem. Cada toque em "Liberar" usa o próximo se o anterior falhou. */
    fun candidates(): List<Uri> {
        val pkg = "org.telegram.messenger"
        fun u(tree: String, doc: String) = Uri.Builder().scheme("content").authority(AUTH)
            .appendPath("tree").appendPath(tree).appendPath("document").appendPath(doc).build()
        return listOf(
            u("primary:A${ZW}ndroid/data", "primary:A${ZW}ndroid/data/$pkg"),   // Android 13/14/15 (truque invisível, pasta do Telegram)
            u("primary:A${ZW}ndroid/data", "primary:A${ZW}ndroid/data"),        // idem, Android/data inteira
            u("primary:Android/data/$pkg", "primary:Android/data/$pkg"),        // Android 13 / alguns 14
            u("primary:Android/data", "primary:Android/data/$pkg"),             // pedido literal
            u("primary:Android/data", "primary:Android/data"),                  // Android 11/12
        )
    }

    fun intent(attempt: Int): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, candidates()[attempt % candidates().size])
        .putExtra("android.provider.extra.SHOW_ADVANCED", true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

    private fun docId(u: Uri) = try { DocumentsContract.getTreeDocumentId(u) } catch (_: Exception) { "" }
    private fun norm(s: String) = s.replace(ZW, "").lowercase()

    /** A árvore aponta para Android/data ou para uma pasta do Telegram dentro dele? */
    fun isTelegramTree(u: Uri): Boolean {
        val d = norm(docId(u)); if (!d.startsWith("primary:android/data")) return false
        val rest = d.removePrefix("primary:android/data").trim('/')
        return rest.isEmpty() || Watched.PKGS.any { rest == it || rest.startsWith("$it/") }
    }

    /** Salva a permissão para sempre e confere se dá para ler. Retorna null se OK, ou a mensagem do problema. */
    fun accept(c: Context, u: Uri): String? {
        try { c.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch (_: Exception) { try { c.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
        if (!isTelegramTree(u)) return "A pasta escolhida não é a do Telegram (${docId(u).replace(ZW, "")})."
        val n = try { c.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(u, docId(u)),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.count } ?: -1 } catch (_: Exception) { -1 }
        if (n <= 0) return "O Android liberou a pasta, mas ela aparece vazia/bloqueada."
        Prefs2.watchedTree = u.toString()
        return null
    }

    /** Todas as árvores do Telegram já liberadas (permissões persistidas). */
    fun trees(c: Context): List<Uri> = c.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission && it.uri.authority == AUTH && isTelegramTree(it.uri) }.map { it.uri }

    fun granted(c: Context) = trees(c).isNotEmpty()
}
