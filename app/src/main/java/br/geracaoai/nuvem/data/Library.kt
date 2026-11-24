package br.geracaoai.nuvem.data

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Uma foto ou vídeo encontrado no aparelho. */
data class LocalFile(
    val path: String,
    val name: String,
    val size: Long,
    val mime: String,
    val taken: Long,
    val bucket: String,
)

/** Acha todas as fotos e vídeos do aparelho e separa ícones, miniaturas e lixo. */
object Library {
    private val ICON_NAME = Regex("(^|[_\\-. ])(icon|icons|ic_|ic-|launcher|logo|avatar|emoji|sticker)", RegexOption.IGNORE_CASE)
    private val JUNK_PATH = Regex("/(\\.cache|cache|\\.trash|\\.trashed-[^/]*|icons?|app_icons?|\\.icons?|Android/data)(/|$)", RegexOption.IGNORE_CASE)
    private val MEDIA_EXT = setOf(
        "jpg", "jpeg", "jpe", "png", "webp", "heic", "heif", "gif", "bmp",
        "mp4", "m4v", "mov", "3gp", "mkv", "webm", "avi",
    )

    /** Ícone de app, logo, cache, miniatura ou imagem pequena quadrada: não é foto de família. */
    fun isIcon(path: String, name: String, size: Long, mime: String): Boolean {
        if (JUNK_PATH.containsMatchIn(path) || ICON_NAME.containsMatchIn(name)) return true
        if (mime.startsWith("video")) return false
        if (size > 1_500_000L) return false
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            o.outWidth > 0 && o.outHeight > 0 && o.outWidth == o.outHeight && o.outWidth <= 1024
        } catch (_: Exception) {
            false
        }
    }

    /** Caminho com pasta escondida (nome começa com ponto), como .thumbnails: fica de fora. */
    fun hidden(path: String): Boolean = path.split('/').any { it.length > 1 && it.startsWith(".") }

    /**
     * Lista tudo: primeiro o índice de mídia do Android (rápido), e, se o acesso a todos
     * os arquivos estiver liberado, também percorre o armazenamento atrás do que o índice não tem.
     */
    fun scan(c: Context): List<LocalFile> {
        val porCaminho = LinkedHashMap<String, LocalFile>()
        val proj = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        c.contentResolver.query(MediaStore.Files.getContentUri("external"), proj, sel, args, null)?.use { cur ->
            val iPath = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val iSize = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMime = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iTaken = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val iMod = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iBucket = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (cur.moveToNext()) {
                val path = cur.getString(iPath) ?: continue
                if (hidden(path)) continue
                val size = cur.getLong(iSize)
                if (size <= 0L) continue
                val name = File(path).name
                val mime = cur.getString(iMime)?.takeIf { it.isNotBlank() } ?: Meta.mimeFromName(name)
                val tirada = cur.getLong(iTaken).takeIf { it > 0L } ?: (cur.getLong(iMod) * 1000L)
                porCaminho[path] = LocalFile(path, name, size, mime, tirada, cur.getString(iBucket) ?: "")
            }
        }

        val todosArquivos = try { Environment.isExternalStorageManager() } catch (_: Throwable) { false }
        if (todosArquivos) {
            try {
                Environment.getExternalStorageDirectory().walkTopDown()
                    .onEnter { d -> !d.name.startsWith(".") && d.name != "Android" }
                    .filter { it.isFile && it.extension.lowercase() in MEDIA_EXT }
                    .forEach { f ->
                        val path = f.absolutePath
                        if (path in porCaminho || hidden(path)) return@forEach
                        val size = f.length()
                        if (size <= 0L) return@forEach
                        porCaminho[path] = LocalFile(
                            path, f.name, size, Meta.mimeFromName(f.name), f.lastModified(), f.parentFile?.name ?: "",
                        )
                    }
            } catch (_: Exception) {
            }
        }
        return porCaminho.values.toList()
    }
}
