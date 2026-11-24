package br.geracaoai.nuvem.data

import android.content.Context
import java.io.File

/**
 * Todas as fotos e vídeos do aparelho: MediaStore + varredura do armazenamento.
 * Pula miniaturas (.thumbnails, thumb…) e a pasta Android/ (dados dos apps). Pastas ocultas que tenham mídia entram.
 */
object MediaScan {
    private val THUMB_DIRS = setOf("thumb", "thumbs", "thumbnail", "thumbnails", ".thumb", ".thumbs", ".thumbnails", "thumbdata", ".thumbdata")
    private const val MAX_FILES = 300_000
    private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "webm", "m4v", "3gp")

    fun isThumbDir(name: String) = name.lowercase() in THUMB_DIRS
    fun isThumbFile(name: String) = name.lowercase().startsWith(".thumbdata")
    fun isThumbPath(path: String): Boolean {
        val parts = path.split('/')
        return parts.dropLast(1).any { isThumbDir(it) } || isThumbFile(parts.last())
    }

    private var cached: Pair<Long, List<LocalItem>>? = null

    /** Guarda o resultado por 10 min: cada backup (inclusive o instantâneo) não relê o celular inteiro. */
    @Synchronized
    fun allAnywhere(c: Context): List<LocalItem> {
        cached?.let { if (System.currentTimeMillis() - it.first < 10 * 60_000L) return it.second }
        val list = scanAll(c)
        cached = System.currentTimeMillis() to list
        return list
    }

    private fun scanAll(c: Context): List<LocalItem> {
        val byPath = LinkedHashMap<String, LocalItem>()
        try { LocalMedia.all(c).forEach { if (!isThumbPath(it.path)) byPath[it.path] = it } } catch (_: Exception) {}
        for (root in LocalMedia.storageRoots()) walk(root, byPath)
        return byPath.values.toList()
    }

    private fun walk(root: File, out: MutableMap<String, LocalItem>) {
        val stack = ArrayDeque<File>()
        stack.add(root)
        var n = 0
        while (stack.isNotEmpty() && n < MAX_FILES) {
            val d = stack.removeFirst()
            val list = try { d.listFiles() } catch (_: Exception) { null } ?: continue
            val bucket = if (d == root) "Armazenamento" else d.name
            for (f in list) {
                if (f.isDirectory) {
                    if (isThumbDir(f.name)) continue
                    if (d == root && f.name == "Android") continue
                    stack.addLast(f)
                } else if (f.isFile && !isThumbFile(f.name) && BackupDiff.isMediaFile(f.name)) {
                    val p = f.absolutePath
                    if (out.containsKey(p)) continue
                    n++
                    val len = try { f.length() } catch (_: Exception) { 0L }
                    if (len <= 0L) continue
                    val mtime = f.lastModified()
                    val ext = f.name.substringAfterLast('.', "").lowercase()
                    val mime = if (ext in VIDEO_EXT) "video/mp4" else "image/jpeg"
                    val lf = ListedFile(p, f.name, len, mtime, mime, bucket, LocalMedia.relFromAbsolute(p), true, 0, mtime)
                    out[p] = LocalMedia.run { lf.toLocalItem() }
                }
            }
        }
    }
}
