package br.geracaoai.nuvem.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.media.MediaScannerConnection
import android.provider.MediaStore
import java.io.File

data class LocalItem(val uri: Uri, val path: String, val name: String, val size: Long, val taken: Long,
                     val mtime: Long, val bucket: String, val relPath: String, val mime: String,
                     val mediaId: Long = 0, val durationMs: Long = 0)

data class Bucket(val name: String, val count: Int)
data class BucketStat(val name: String, val count: Int, val bytes: Long)

object LocalMedia {
    private val coll = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val mediaSel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
    private val itemProj = arrayOf(
        MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.SIZE, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME, MediaStore.Files.FileColumns.RELATIVE_PATH,
        MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE)

    fun all(c: Context, buckets: Set<String>? = null): List<LocalItem> = query(c, buckets, exact = null)

    /** Só os itens de uma pasta, já em ordem de data. Não lê as outras pastas. */
    fun inBucket(c: Context, bucket: String): List<LocalItem> =
        query(c, setOf(bucket), exact = bucket).sortedWith(compareBy<LocalItem> { it.taken }.thenBy { it.mediaId })

    /** Contagem leve (sem caminho de arquivo) para progresso e lista de pastas. */
    fun countByBucket(c: Context, buckets: Set<String>? = null): Map<String, Int> {
        val proj = arrayOf(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME, MediaStore.Files.FileColumns.MEDIA_TYPE)
        val out = HashMap<String, Int>()
        c.contentResolver.query(coll, proj, mediaSel, null, null)?.use { k ->
            while (k.moveToNext()) {
                val b = k.getString(0) ?: ""
                if (buckets != null && b !in buckets) continue
                out[b] = (out[b] ?: 0) + 1
            }
        }
        return out
    }

    fun buckets(c: Context): List<Bucket> =
        countByBucket(c).map { Bucket(it.key, it.value) }.sortedByDescending { it.count }

    /** Contagem e tamanho por pasta (só pra UI de progresso). */
    fun statsByBucket(c: Context, buckets: Set<String>? = null): Map<String, BucketStat> {
        val proj = arrayOf(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.MEDIA_TYPE)
        val count = HashMap<String, Int>()
        val bytes = HashMap<String, Long>()
        c.contentResolver.query(coll, proj, mediaSel, null, null)?.use { k ->
            while (k.moveToNext()) {
                val b = k.getString(0) ?: ""
                if (buckets != null && b !in buckets) continue
                count[b] = (count[b] ?: 0) + 1
                bytes[b] = (bytes[b] ?: 0L) + k.getLong(1).coerceAtLeast(0L)
            }
        }
        return count.mapValues { (n, c) -> BucketStat(n, c, bytes[n] ?: 0L) }
    }

    private fun query(c: Context, buckets: Set<String>?, exact: String?): List<LocalItem> {
        val (sel, args) = if (exact != null) {
            "$mediaSel AND ${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} = ?" to arrayOf(exact)
        } else mediaSel to null
        val order = "${MediaStore.MediaColumns.DATE_TAKEN} ASC, ${MediaStore.Files.FileColumns.DATE_MODIFIED} ASC, ${MediaStore.Files.FileColumns._ID} ASC"
        val out = ArrayList<LocalItem>()
        c.contentResolver.query(coll, itemProj, sel, args, order)?.use { k ->
            while (k.moveToNext()) {
                val bucket = k.getString(6) ?: ""
                if (buckets != null && bucket !in buckets) continue
                val path = k.getString(1) ?: continue
                val isVideo = k.getInt(9) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                val id = k.getLong(0)
                val mtime = k.getLong(5) * 1000
                out += LocalItem(
                    ContentUris.withAppendedId(base, id), path, k.getString(2) ?: path.substringAfterLast('/'),
                    k.getLong(3), k.getLong(4).takeIf { it > 0 } ?: mtime, mtime, bucket, k.getString(7) ?: "", k.getString(8) ?: "", id)
            }
        }
        return out
    }

    /** MediaStore + varredura do disco na pasta real. Acha arquivo movido com data antiga. */
    fun scanAndWalk(c: Context, bucket: String): List<LocalItem> {
        val store = queryForFolder(c, bucket)
        rememberRootsFrom(store)
        val roots = folderDirs(bucket)
        val disk = ArrayList<ListedFile>()
        for (dir in roots) disk += walkDir(dir, bucket)
        for (u in FolderTrees.uris(bucket)) {
            try { disk += FolderAccess.walkSaf(c, android.net.Uri.parse(u), bucket) } catch (_: Exception) {}
        }
        val foundPaths = disk.map { it.path }.filter { p -> p.startsWith("/") && store.none { it.path == p } }
        if (foundPaths.isNotEmpty()) requestScan(c, foundPaths)
        val after = if (foundPaths.isNotEmpty()) queryForFolder(c, bucket) else store
        rememberRootsFrom(after)
        val more = BackupDiff.rememberRelPaths(disk)
        if (more.isNotEmpty()) FolderRoots.add(more)
        val storeListed = after.map { it.toListed() }
        val merged = BackupDiff.mergeListings(storeListed, disk)
        val byPath = after.associateBy { BackupDiff.normalizePath(it.path) }
        return merged.map { lf ->
            byPath[BackupDiff.normalizePath(lf.path)]?.copy(durationMs = lf.durationMs.takeIf { it > 0 } ?: 0)
                ?: lf.toLocalItem()
        }.filter { item ->
            val f = File(item.path)
            f.isFile && (f.canRead() || item.mediaId > 0 || AllFilesAccess.granted())
        }
    }

    fun queryForFolder(c: Context, bucket: String): List<LocalItem> {
        val fromBucket = try { inBucket(c, bucket) } catch (_: Exception) { emptyList() }
        val extra = ArrayList<LocalItem>()
        try {
            c.contentResolver.query(coll, itemProj, mediaSel, null, null)?.use { k ->
                while (k.moveToNext()) {
                    val path = k.getString(1) ?: continue
                    val b = k.getString(6) ?: ""
                    val rel = k.getString(7) ?: ""
                    if (b == bucket) continue
                    if (!BackupDiff.pathBelongsToFolder(path, rel, bucket) && !BackupDiff.pathBelongsToFolder(path, "", bucket)) continue
                    val isVideo = k.getInt(9) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    val id = k.getLong(0)
                    val mtime = k.getLong(5) * 1000
                    extra += LocalItem(
                        ContentUris.withAppendedId(base, id), path, k.getString(2) ?: path.substringAfterLast('/'),
                        k.getLong(3), k.getLong(4).takeIf { it > 0 } ?: mtime, mtime, if (b.isBlank()) bucket else b, rel, k.getString(8) ?: "", id)
                }
            }
        } catch (_: Exception) {}
        val seen = HashSet<String>()
        val out = ArrayList<LocalItem>()
        for (i in fromBucket + extra) {
            val k = BackupDiff.normalizePath(i.path)
            if (k in seen) continue
            seen += k
            out += i
        }
        return out.sortedWith(compareBy<LocalItem> { it.taken }.thenBy { it.mediaId })
    }

    fun rememberRootsFrom(items: List<LocalItem>) {
        if (items.isEmpty()) return
        val listed = items.map { it.toListed() }
        val more = BackupDiff.rememberRelPaths(listed)
        FolderRoots.add(more)
    }

    fun folderDirs(bucket: String): List<File> {
        val rels = FolderRoots.forBucket(bucket) + BackupDiff.candidateRelPaths(bucket, FolderRoots.forBucket(bucket))
        val roots = storageRoots()
        val out = LinkedHashSet<File>()
        for (root in roots) for (rel in rels) {
            val d = File(root, rel.trim('/'))
            if (d.isDirectory) out += d
        }
        // caminho absoluto gravado em FolderRoots (às vezes vem completo)
        for (rel in FolderRoots.forBucket(bucket)) {
            val raw = rel.trim().trim('/')
            if (raw.startsWith("storage/") || raw.startsWith("/")) {
                val f = File("/" + raw.trimStart('/'))
                if (f.isDirectory) out += f
            }
        }
        try { FolderAccess.findDirsNamed(bucket).forEach { if (it.isDirectory) out += it } } catch (_: Exception) {}
        return out.toList()
    }

    fun storageRoots(): List<File> {
        val out = LinkedHashSet<File>()
        try { Environment.getExternalStorageDirectory()?.let { if (it.isDirectory) out += it } } catch (_: Exception) {}
        out += File("/storage/emulated/0")
        return out.filter { it.isDirectory }
    }

    fun walkDir(dir: File, bucket: String): List<ListedFile> {
        val out = ArrayList<ListedFile>()
        if (!dir.isDirectory) return out
        val stack = ArrayDeque<File>()
        stack.add(dir)
        var n = 0
        while (stack.isNotEmpty() && n < 20_000) {
            val d = stack.removeFirst()
            val list = try { d.listFiles() } catch (_: Exception) { null } ?: continue
            for (f in list) {
                if (f.isDirectory) {
                    val root = d == dir
                    if (!DiscoverLogic.shouldEnterChild(d.name, f.name, isWalkRoot = root && f == dir)) {
                        if (!DiscoverLogic.shouldEnterDir(f.name, isWalkRoot = false)) continue
                    }
                    if (!DiscoverLogic.shouldEnterChild(d.name, f.name)) continue
                    stack.addLast(f)
                } else if (f.isFile && BackupDiff.isMediaFile(f.name)) {
                    val mime = if (f.name.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".mov") || it.endsWith(".webm") || it.endsWith(".m4v") || it.endsWith(".3gp") }) "video/mp4" else "image/jpeg"
                    val rel = relFromAbsolute(f.absolutePath)
                    val readable = try { f.canRead() && f.length() >= 0L } catch (_: Exception) { false }
                    out += ListedFile(f.absolutePath, f.name, f.length(), f.lastModified(), mime, bucket, rel, readable || f.exists(), 0, f.lastModified())
                    n++
                }
            }
        }
        return out
    }

    fun relFromAbsolute(path: String): String {
        val p = BackupDiff.normalizePath(path)
        for (m in listOf("/DCIM/", "/Pictures/", "/Movies/", "/Download/", "/Downloads/", "/Documents/")) {
            val i = p.indexOf(m)
            if (i >= 0) return p.substring(i + 1, p.length - p.substringAfterLast('/').length)
        }
        return ""
    }

    fun requestScan(c: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        try { MediaScannerConnection.scanFile(c, paths.take(400).toTypedArray(), null, null) } catch (_: Exception) {}
    }

    fun LocalItem.toListed() = ListedFile(path, name, size, mtime, mime, bucket, relPath, try { File(path).canRead() } catch (_: Exception) { false }, mediaId, taken, durationMs)

    fun ListedFile.toLocalItem(): LocalItem {
        val isVideo = mime.startsWith("video/") || name.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".mov") || it.endsWith(".webm") }
        val uri = if (mediaId > 0) {
            val base = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            ContentUris.withAppendedId(base, mediaId)
        } else android.net.Uri.fromFile(File(path))
        return LocalItem(uri, path, name, size, taken.takeIf { it > 0 } ?: mtime, mtime, bucket, relPath, mime, mediaId, durationMs)
    }
}
