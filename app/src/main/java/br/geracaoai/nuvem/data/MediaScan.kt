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

    @Synchronized
    fun invalidate() { cached = null }

    /** Guarda o resultado por 10 min: cada backup (inclusive o instantâneo) não relê o celular inteiro. */
    @Synchronized
    fun allAnywhere(c: Context): List<LocalItem> {
        cached?.let { if (System.currentTimeMillis() - it.first < 10 * 60_000L) return it.second }
        val list = scanAll(c).filterNot { isIcon(it) }
        cached = System.currentTimeMillis() to list
        return list
    }

    private val ICON_NAME = Regex("(^|[_\\-. ])(icon|icons|ic_|ic-|launcher|logo|avatar|emoji|sticker)", RegexOption.IGNORE_CASE)
    private val JUNK_PATH = Regex("/(\\.cache|cache|\\.trash|\\.trashed-[^/]*|icons?|app_icons?|\\.icons?|Android/data)(/|$)", RegexOption.IGNORE_CASE)

    /** Ícone de app, logo, cache ou imagem pequena quadrada: não é foto/vídeo de família. */
    fun isIcon(it: LocalItem): Boolean = isIconFile(it.path, it.name, it.size, it.mime)

    fun isIconFile(path: String, name: String, size: Long, mime: String): Boolean {
        if (JUNK_PATH.containsMatchIn(path) || ICON_NAME.containsMatchIn(name)) return true
        if (mime.startsWith("video")) return false
        if (size > 1_500_000L) return false
        return try {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, o)
            o.outWidth > 0 && o.outHeight > 0 && o.outWidth == o.outHeight && o.outWidth <= 1024
        } catch (_: Exception) { false }
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
                    // Android/data e obb são de apps (bloqueados); Android/media tem WhatsApp/Telegram novos.
                    if (d == root && f.name == "Android") { File(f, "media").takeIf { it.isDirectory }?.let { stack.addLast(it) }; continue }
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
