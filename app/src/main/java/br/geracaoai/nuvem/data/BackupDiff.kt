package br.geracaoai.nuvem.data

import java.util.Locale

/** Arquivo visto na galeria (MediaStore) ou no disco, pra achar o que o índice ainda não tem. */
data class ListedFile(
    val path: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    val mime: String = "",
    val bucket: String = "",
    val relPath: String = "",
    val readable: Boolean = true,
    val mediaId: Long = 0,
    val taken: Long = 0,
    val durationMs: Long = 0,
)

data class CloudIdentity(val path: String, val name: String, val size: Long, val hash: String)

/**
 * Junta MediaStore + arquivos reais na pasta e decide o que ainda precisa de backup.
 * Não usa só o nome do álbum nem a data: um arquivo movido (com data antiga) entra igual.
 */
object BackupDiff {
    private val mediaExt = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "dng", "cr2", "nef", "arw",
        "mp4", "mkv", "mov", "webm", "avi", "m4v", "3gp", "ts", "mpeg", "mpg",
    )

    fun isMediaFile(name: String, mime: String = ""): Boolean {
        if (mime.startsWith("image/") || mime.startsWith("video/")) return true
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in mediaExt
    }

    fun normalizePath(path: String): String = path.trim().replace('\\', '/').trimEnd('/')

    fun pathBelongsToFolder(path: String, relPath: String, bucket: String): Boolean {
        val p = normalizePath(path).lowercase(Locale.ROOT)
        val rel = relPath.trim('/').lowercase(Locale.ROOT)
        val b = bucket.trim('/').lowercase(Locale.ROOT)
        if (b.isNotBlank()) {
            if (p.contains("/$b/") || p.endsWith("/$b") || p.substringAfterLast('/') == b) {
                // parent folder name equals the bucket (…/Segura/video.mp4)
                val parent = p.substringBeforeLast('/').substringAfterLast('/')
                if (parent == b) return true
            }
        }
        if (rel.isNotBlank()) {
            val relNorm = rel.trimEnd('/') + "/"
            if (p.contains("/$relNorm") || p.endsWith("/${rel.trimEnd('/')}")) return true
        }
        return false
    }

    fun candidateRelPaths(bucket: String, knownRel: Collection<String> = emptyList()): List<String> {
        val b = bucket.trim('/')
        val out = LinkedHashSet<String>()
        for (r in knownRel) {
            val t = r.trim().replace('\\', '/').trim('/')
            if (t.isNotBlank()) out += "$t/"
        }
        if (b.isNotBlank()) {
            listOf("DCIM/$b", "Pictures/$b", "Movies/$b", "Download/$b", "Downloads/$b", "Documents/$b", b).forEach { out += "$it/" }
        }
        return out.toList()
    }

    fun rememberRelPaths(items: List<ListedFile>): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        for (i in items) {
            val b = i.bucket
            if (b.isBlank()) continue
            val rel = i.relPath.trim().ifBlank {
                val n = normalizePath(i.path)
                val cut = n.substringBeforeLast('/', "")
                val idx = listOf("/DCIM/", "/Pictures/", "/Movies/", "/Download/", "/Downloads/").map { cut.indexOf(it) }.firstOrNull { it >= 0 } ?: -1
                if (idx >= 0) cut.substring(idx + 1) else ""
            }
            if (rel.isNotBlank()) out.getOrPut(b) { HashSet() }.add(rel.trimEnd('/') + "/")
        }
        return out
    }

    /** Une as duas listas. Prefere o caminho que ainda existe; não perde arquivo só porque o MediaStore atrasou. */
    fun mergeListings(store: List<ListedFile>, disk: List<ListedFile>): List<ListedFile> {
        val byPath = LinkedHashMap<String, ListedFile>()
        fun put(f: ListedFile) {
            val k = normalizePath(f.path)
            if (k.isBlank()) return
            val prev = byPath[k]
            if (prev == null) byPath[k] = f.copy(path = k)
            else if (f.readable && !prev.readable) byPath[k] = f.copy(path = k)
            else if (f.mediaId > 0 && prev.mediaId <= 0) byPath[k] = prev.copy(mediaId = f.mediaId, mime = prev.mime.ifBlank { f.mime })
        }
        store.forEach(::put)
        disk.forEach(::put)

        val unreadable = byPath.values.filter { !it.readable }
        if (unreadable.isNotEmpty()) {
            val readable = byPath.values.filter { it.readable }
            for (u in unreadable) {
                val match = readable.firstOrNull { it.name.equals(u.name, true) && it.size == u.size && it.size > 0 }
                if (match != null) byPath.remove(normalizePath(u.path))
            }
        }
        return byPath.values.sortedWith(compareBy<ListedFile> { it.taken.takeIf { t -> t > 0 } ?: it.mtime }.thenBy { it.path })
    }

    fun isUploaded(path: String, size: Long, hash: String?, index: List<CloudIdentity>): Boolean {
        if (!hash.isNullOrBlank()) {
            if (index.any { it.hash == hash && (it.size == size || it.size <= 0L) }) return true
        }
        val p = normalizePath(path)
        if (p.isNotBlank() && size > 0L && index.any { normalizePath(it.path) == p && it.size == size }) return true
        return false
    }

    /** Nome+tamanho só conta se o caminho for o mesmo (evita pular um arquivo novo com nome repetido). */
    fun nameSizeSamePath(path: String, name: String, size: Long, index: List<CloudIdentity>): Boolean {
        if (name.isBlank() || name == "item" || size <= 0L) return false
        val p = normalizePath(path)
        return index.any { it.name == name && it.size == size && (p.isBlank() || normalizePath(it.path) == p) }
    }

    fun pending(files: List<ListedFile>, hashOf: (ListedFile) -> String?, index: List<CloudIdentity>): List<ListedFile> =
        files.filter { it.readable && it.size > 0L && isMediaFile(it.name, it.mime) && !isUploaded(it.path, it.size, hashOf(it), index) }

    fun biggestFirst(files: List<ListedFile>): List<ListedFile> =
        files.sortedWith(compareByDescending<ListedFile> { it.size }.thenByDescending { it.taken }.thenBy { it.path })

    fun bytesOf(files: List<ListedFile>): Long = files.sumOf { it.size.coerceAtLeast(0L) }
}
