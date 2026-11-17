package br.geracaoai.nuvem.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class LocalItem(val uri: Uri, val path: String, val name: String, val size: Long, val taken: Long,
                     val mtime: Long, val bucket: String, val relPath: String, val mime: String,
                     val mediaId: Long = 0)

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
}
