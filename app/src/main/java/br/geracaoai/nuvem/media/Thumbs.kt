package br.geracaoai.nuvem.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ThumbnailUtils
import android.util.LruCache
import android.util.Size
import br.geracaoai.nuvem.tg.CloudRec
import br.geracaoai.nuvem.tg.Tg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Miniaturas e imagens. Usa o arquivo do celular quando ele existe;
 * senão pega a miniatura ou o arquivo no Telegram.
 */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val sem = Semaphore(3)

    fun localOk(p: String): Boolean = p.isNotBlank() && File(p).let { it.exists() && it.canRead() }

    /** Erro de rede ou de mensagem apagada não pode derrubar a tela: vira "sem imagem". */
    private inline fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Miniatura quadrada para a grade. */
    suspend fun grid(item: CloudRec, px: Int = 320): Bitmap? {
        val key = "g${item.chatId}:${item.msgId}:$px"
        cache.get(key)?.let { return it }
        val b = withContext(Dispatchers.IO) { sem.withPermit { safe { gridInner(item, px) } } }
        if (b != null) cache.put(key, b)
        return b
    }

    /** Imagem grande, para ver em tela cheia. */
    suspend fun full(item: CloudRec): Bitmap? = withContext(Dispatchers.IO) {
        safe {
            val path: String? = if (localOk(item.meta.path)) {
                item.meta.path
            } else {
                val m = Tg.mediaOf(item.chatId, item.msgId)
                if (m == null) null else if (localOk(m.localPath)) m.localPath else Tg.download(m.fileId, 32)
            }
            path?.let { decodeImage(it, 2048) }
        }
    }

    /** Caminho de um vídeo pronto para tocar: o do celular, ou baixa do Telegram. */
    suspend fun videoPath(item: CloudRec): String? = withContext(Dispatchers.IO) {
        safe {
            if (localOk(item.meta.path)) return@safe item.meta.path
            val m = Tg.mediaOf(item.chatId, item.msgId) ?: return@safe null
            if (localOk(m.localPath)) return@safe m.localPath
            Tg.download(m.fileId, 32)
        }
    }

    private suspend fun gridInner(item: CloudRec, px: Int): Bitmap? {
        if (localOk(item.meta.path)) return decodeLocal(item.meta.path, item.meta.isVideo, px)
        val m = Tg.mediaOf(item.chatId, item.msgId) ?: return null
        if (localOk(m.localPath)) return decodeLocal(m.localPath, item.meta.isVideo, px)
        m.thumbFileId?.let { id ->
            Tg.download(id, 16)?.let { p -> decodeImage(p, px)?.let { return it } }
        }
        m.mini?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        }
        if (item.meta.isVideo) return null
        val p = Tg.download(m.fileId, 16) ?: return null
        return decodeImage(p, px)
    }

    private fun decodeLocal(path: String, video: Boolean, px: Int): Bitmap? =
        if (video) {
            try {
                ThumbnailUtils.createVideoThumbnail(File(path), Size(px, px), null)
            } catch (_: Exception) {
                null
            }
        } else {
            decodeImage(path, px)
        }

    /** Decodifica respeitando a rotação da foto e reduzindo para caber em [maxSide]. */
    fun decodeImage(path: String, maxSide: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val maior = maxOf(w, h)
            if (maior > maxSide) {
                val k = maxSide.toFloat() / maior
                dec.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
            }
        }
    } catch (_: Exception) {
        null
    }
}
