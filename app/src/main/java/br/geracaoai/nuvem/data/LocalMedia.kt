package br.geracaoai.nuvem.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class LocalItem(val uri: Uri, val path: String, val name: String, val size: Long, val taken: Long,
                     val mtime: Long, val bucket: String, val relPath: String, val mime: String)

data class Bucket(val name: String, val count: Int)

object LocalMedia {
    private val coll = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun all(c: Context, buckets: Set<String>? = null): List<LocalItem> {
        val proj = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME, MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE)
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val out = ArrayList<LocalItem>()
        c.contentResolver.query(coll, proj, sel, null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { k ->
            while (k.moveToNext()) {
                val bucket = k.getString(6) ?: ""
                if (buckets != null && bucket !in buckets) continue
                val path = k.getString(1) ?: continue
                val isVideo = k.getInt(9) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                val mtime = k.getLong(5) * 1000
                out += LocalItem(ContentUris.withAppendedId(base, k.getLong(0)), path, k.getString(2) ?: path.substringAfterLast('/'),
                    k.getLong(3), k.getLong(4).takeIf { it > 0 } ?: mtime, mtime, bucket, k.getString(7) ?: "", k.getString(8) ?: "")
            }
        }
        return out
    }

    fun buckets(c: Context): List<Bucket> = all(c).groupingBy { it.bucket }.eachCount().map { Bucket(it.key, it.value) }.sortedByDescending { it.count }
}
