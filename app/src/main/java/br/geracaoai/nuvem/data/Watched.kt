package br.geracaoai.nuvem.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import br.geracaoai.nuvem.shizuku.ShizState
import br.geracaoai.nuvem.shizuku.Shiz
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Vídeo encontrado no cache/pastas do Telegram. [src] = "shizuku", "saf" ou "file". */
data class WatchedFile(val src: String, val id: String, val name: String, val size: Long, val mtime: Long) {
    val key get() = "${name.lowercase()}|$size"
}

object Watched {
    const val TAG = "#assistido"
    const val MIN_SIZE = 300L * 1024
    val PKGS = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta", "org.telegram.plus", "org.telegram.messenger.regular")
    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "avi")
    private val SKIP_EXT = setOf("jpg", "jpeg", "png", "webp", "tgs", "temp", "pt", "part", "json", "db", "txt", "ogg", "opus", "mp3", "m4a", "pdf", "zip", "apk")

    fun roots(): List<String> {
        val ext = Environment.getExternalStorageDirectory().absolutePath
        return PKGS.flatMap { listOf("$ext/Android/data/$it/cache", "$ext/Android/data/$it/files") } + listOf("$ext/Telegram", "$ext/Movies/Telegram", "$ext/Download/Telegram")
    }

    /** Decide pelo nome + "magic bytes" (ftyp = MP4/MOV, EBML = MKV/WebM). Retorna o mime ou null. */
    fun sniff(name: String, head: ByteArray): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in SKIP_EXT) return null
        val low = name.lowercase()
        if ("thumb" in low || "sticker" in low) return null
        if (head.size >= 12 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()) {
            val brand = String(head, 8, 4, Charsets.ISO_8859_1)
            if (brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("mif1") || brand.startsWith("avif") || brand.startsWith("M4A")) return null
            return if (brand.startsWith("qt")) "video/quicktime" else "video/mp4"
        }
        if (head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte())
            return if (ext == "webm") "video/webm" else "video/x-matroska"
        return null
    }

    fun extFor(mime: String) = when (mime) { "video/webm" -> "webm"; "video/x-matroska" -> "mkv"; "video/quicktime" -> "mov"; else -> "mp4" }

    // ---------- Descoberta ----------
    suspend fun discover(c: Context): List<WatchedFile> {
        val out = LinkedHashMap<String, WatchedFile>()
        if (Shiz.state() == ShizState.READY) Shiz.service()?.let { s ->
            for (r in roots()) try {
                s.list(r, MIN_SIZE).forEach { line ->
                    val p = line.split('|', limit = 3); if (p.size == 3) {
                        val f = WatchedFile("shizuku", p[2], p[2].substringAfterLast('/'), p[0].toLong(), p[1].toLong()); out.putIfAbsent(f.key, f) }
                }
            } catch (_: Exception) {}
        }
        Prefs2.watchedTree.takeIf { it.isNotBlank() }?.let { t -> try { walkTree(c, Uri.parse(t)).forEach { out.putIfAbsent(it.key, it) } } catch (_: Exception) {} }
        // Pastas públicas do Telegram indexadas pelo Android (sem Shizuku, via permissão de vídeos).
        try {
            c.contentResolver.query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED),
                "${MediaStore.Video.Media.DATA} LIKE ?", arrayOf("%/Telegram/%"), null)?.use { k ->
                while (k.moveToNext()) { val p = k.getString(0) ?: continue
                    val f = WatchedFile("file", p, p.substringAfterLast('/'), k.getLong(1), k.getLong(2) * 1000); if (f.size >= MIN_SIZE) out.putIfAbsent(f.key, f) }
            }
        } catch (_: Exception) {}
        return out.values.sortedByDescending { it.mtime }
    }

    private fun walkTree(c: Context, tree: Uri): List<WatchedFile> {
        val out = ArrayList<WatchedFile>()
        fun walk(docId: String, depth: Int) {
            if (depth > 6) return
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            c.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { k ->
                while (k.moveToNext()) {
                    val id = k.getString(0); val name = k.getString(1) ?: ""
                    if (k.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { if (!name.equals("Stickers", true)) walk(id, depth + 1) }
                    else if (k.getLong(3) >= MIN_SIZE) out += WatchedFile("saf", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, k.getLong(3), k.getLong(4))
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return out
    }

    suspend fun open(c: Context, f: WatchedFile): InputStream? = when (f.src) {
        "shizuku" -> Shiz.service()?.open(f.id)?.let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it) }
        "saf" -> c.contentResolver.openInputStream(Uri.parse(f.id))
        else -> File(f.id).inputStream()
    }

    /** Apaga a cópia local (cache do Telegram). [ref] = "src:id" gravado na legenda. */
    suspend fun deleteLocal(c: Context, ref: String): Boolean = try {
        val src = ref.substringBefore(':'); val id = ref.substringAfter(':')
        when (src) {
            "shizuku" -> Shiz.service()?.remove(id) == true
            "saf" -> DocumentsContract.deleteDocument(c.contentResolver, Uri.parse(id))
            "file" -> File(id).delete()
            else -> false
        }
    } catch (_: Exception) { false }

    // ---------- Sincronização ----------
    class Progress(val done: Int, val total: Int, val name: String, val pct: Int)

    /** Envia para a nuvem cada vídeo novo. Retorna quantos foram enviados. Cancelável (botão Parar). */
    suspend fun sync(c: Context, onProgress: (Progress) -> Unit): Int {
        val chat = Prefs.chatId; if (chat == 0L) return 0
        val todo = discover(c).filter { !Db.seen(it.key) && !Db.seen("bad:${it.key}:${it.mtime}") }
        val tmpDir = File(c.cacheDir, "assistidos").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        var sent = 0
        todo.forEachIndexed { idx, f ->
            coroutineContext.ensureActive()
            if (Prefs2.watchedPaused) return sent
            onProgress(Progress(idx, todo.size, f.name, 0))
            val ins = try { open(c, f) } catch (_: Exception) { null } ?: return@forEachIndexed
            val head = ByteArray(16)
            var tmp: File? = null; var thumb: File? = null
            try {
                ins.use { s ->
                    var n = 0; while (n < 16) { val r = s.read(head, n, 16 - n); if (r < 0) break; n += r }
                    val mime = sniff(f.name, head.copyOf(n))
                    if (mime == null) { Db.markSeen(f.key); return@forEachIndexed }
                    val base = f.name.substringBeforeLast('.').ifBlank { "video" }
                    val name = if (f.name.substringAfterLast('.', "").lowercase() in VIDEO_EXT) f.name else "$base.${extFor(mime)}"
                    tmp = File(tmpDir, name)
                    val md = MessageDigest.getInstance("SHA-256")
                    FileOutputStream(tmp!!).use { o ->
                        o.write(head, 0, n); md.update(head, 0, n)
                        val buf = ByteArray(1 shl 16)
                        while (true) { coroutineContext.ensureActive(); val r = s.read(buf); if (r < 0) break; o.write(buf, 0, r); md.update(buf, 0, r) }
                    }
                    val t = tmp!!
                    val hash = md.digest().joinToString("") { "%02x".format(it) }
                    if (Db.cloudByHash(chat, hash)?.size == t.length()) { Db.markSeen(f.key); return@forEachIndexed }
                    // Vídeo incompleto (assistido só um pedaço) não abre: ignora por enquanto e tenta de novo depois.
                    val dur = try { MediaMetadataRetriever().run { setDataSource(t.absolutePath); val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0; thumb = frame(this, tmpDir); release(); d } } catch (_: Exception) { 0L }
                    if (dur <= 0) { Db.markSeen("bad:${f.key}:${f.mtime}"); return@forEachIndexed }
                    val meta = Meta(hash, t.length(), f.mtime, name, mime, WATCHED_FOLDER, "${f.src}:${f.id}")
                    var last = -1
                    Repo.uploadFile(t.absolutePath, t.length(), "$name\n$TAG\n" + meta.toCaption().substringAfter('\n'), thumb) { p ->
                        val pct = (p * 100).toInt(); if (pct != last) { last = pct; onProgress(Progress(idx, todo.size, name, pct)) } }
                    Db.markSeen(f.key); sent++
                }
            } finally { tmp?.delete(); thumb?.delete() }
        }
        Prefs2.watchedLast = System.currentTimeMillis()
        return sent
    }

    private fun frame(r: MediaMetadataRetriever, dir: File): File? = try {
        val b = r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320) ?: r.frameAtTime ?: return null
        val f = File(dir, "thumb_${System.nanoTime()}.jpg"); FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }; f
    } catch (_: Exception) { null }
}
