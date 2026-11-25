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

    /** Só os arquivos soltos na raiz do armazenamento (sem subpastas). */
    const val FLAT = "dirflat:"

    fun keyFor(path: String) = PREFIX + path.trimEnd('/')
    fun isKey(key: String) = key.startsWith(PREFIX) || key.startsWith(FLAT)
    fun pathOf(key: String) = key.removePrefix(PREFIX).removePrefix(FLAT)
    fun nameOf(key: String) = if (key.startsWith(FLAT)) "Celular" else pathOf(key).substringAfterLast('/').ifBlank { "Arquivos" }

    /** Raiz do armazenamento (/storage/emulated/0): "todos os arquivos do celular". */
    fun rootPath(): String = (LocalMedia.storageRoots().firstOrNull() ?: File("/storage/emulated/0")).absolutePath.trimEnd('/')
    fun isRoot(path: String): Boolean = path.trimEnd('/').let { it == rootPath() || it == "/storage/emulated/0" }
    val allPhone get() = Prefs.fileFolders.any { isRoot(it) }

    /**
     * Pastas marcadas para backup no modo Arquivos.
     * O celular inteiro vira uma chave por pasta principal (Documents, Download…), cada uma no seu álbum,
     * mais os arquivos soltos da raiz. A pasta Android/ (dados de apps) e as ocultas ficam de fora.
     */
    fun keys(): Set<String> = Prefs.fileFolders.flatMap { p ->
        if (!isRoot(p)) listOf(keyFor(p))
        else {
            val dirs = (try { File(p).listFiles() } catch (_: Exception) { null } ?: emptyArray())
                .filter { it.isDirectory && !it.name.startsWith(".") && !it.name.equals("Android", true) }
            dirs.map { keyFor(it.absolutePath) } + (FLAT + p.trimEnd('/'))
        }
    }.toSet()

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
        val recursive = !key.startsWith(FLAT)
        val out = ArrayList<LocalItem>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && out.size < MAX_FILES) {
            val d = stack.removeFirst()
            val list = try { d.listFiles() } catch (_: Exception) { null } ?: continue
            for (f in list) {
                if (f.isDirectory) {
                    if (!recursive || f.name.startsWith(".") || f.name.lowercase() in SKIP_DIRS) continue
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
