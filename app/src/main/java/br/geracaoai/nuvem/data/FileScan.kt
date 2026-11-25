package br.geracaoai.nuvem.data

import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Backup de pasta inteira (modo Arquivos): qualquer tipo de arquivo, com subpastas.
 * A chave da pasta no backup é "dir:" + caminho absoluto.
 */
object FileScan {
    const val PREFIX = "dir:"
    private const val MAX_FILES = 200_000
    private val SKIP_DIRS = setOf(".thumbnails", ".thumbnail", ".trash", ".cache", "cache")

    fun keyFor(path: String) = PREFIX + path.trimEnd('/')
    fun isKey(key: String) = key.startsWith(PREFIX)
    fun pathOf(key: String) = key.removePrefix(PREFIX)
    fun nameOf(key: String) = pathOf(key).substringAfterLast('/').ifBlank { "Arquivos" }

    /** Pastas marcadas para backup no modo Arquivos (caminhos absolutos). */
    fun keys(): Set<String> = Prefs.fileFolders.map { keyFor(it) }.toSet()

    /** A pasta (ou uma pasta acima dela) já está no backup? */
    fun isCovered(path: String): Boolean {
        val p = path.trimEnd('/')
        return Prefs.fileFolders.any { f -> val b = f.trimEnd('/'); p == b || p.startsWith("$b/") }
    }

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        Meta.mimeFromName(name).takeIf { it.isNotBlank() }?.let { return it }
        return try { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) } catch (_: Exception) { null }
            ?: "application/octet-stream"
    }

    /** Todos os arquivos da pasta e subpastas, do mais antigo para o mais novo. */
    fun walk(key: String): List<LocalItem> {
        val root = File(pathOf(key))
        if (!root.isDirectory) return emptyList()
        val album = nameOf(key)
        val out = ArrayList<LocalItem>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && out.size < MAX_FILES) {
            val d = stack.removeFirst()
            val list = try { d.listFiles() } catch (_: Exception) { null } ?: continue
            for (f in list) {
                if (f.isDirectory) {
                    if (f.name.lowercase() in SKIP_DIRS) continue
                    stack.addLast(f)
                } else if (f.isFile) {
                    val size = try { f.length() } catch (_: Exception) { 0L }
                    if (size <= 0L) continue
                    val mtime = f.lastModified()
                    val rel = relOf(d.absolutePath)
                    out += LocalItem(Uri.fromFile(f), f.absolutePath, f.name, size, mtime, mtime, album, rel, mimeOf(f.name))
                }
            }
        }
        return out.sortedWith(compareBy<LocalItem> { it.taken }.thenBy { it.path })
    }

    /** Caminho relativo ao armazenamento ("Documents/Trabalho/"), usado para restaurar no mesmo lugar. */
    fun relOf(dir: String): String {
        val p = dir.trimEnd('/') + "/"
        val m = Regex("^/storage/[^/]+/(\\d+/)?").find(p)
        val rest = (if (m != null) p.substring(m.range.last + 1) else p.trimStart('/')).trimEnd('/')
        return if (rest.isBlank()) "" else "$rest/"
    }

    data class Entry(val file: File, val isDir: Boolean, val size: Long, val mtime: Long, val children: Int)

    /** Conteúdo de uma pasta para o navegador de arquivos (pastas primeiro). */
    fun list(dir: File, showHidden: Boolean = false): List<Entry> {
        val list = try { dir.listFiles() } catch (_: Exception) { null } ?: return emptyList()
        return list.filter { showHidden || !it.name.startsWith(".") }.map { f ->
            if (f.isDirectory) Entry(f, true, 0L, f.lastModified(), try { f.list()?.size ?: 0 } catch (_: Exception) { 0 })
            else Entry(f, false, f.length(), f.lastModified(), 0)
        }.sortedWith(compareBy<Entry> { !it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name })
    }
}
