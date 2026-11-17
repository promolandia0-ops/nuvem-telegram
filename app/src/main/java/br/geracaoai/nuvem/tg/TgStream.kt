package br.geracaoai.nuvem.tg

import br.geracaoai.nuvem.data.PlaybackFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.drinkless.tdlib.TdApi

data class StreamProgress(
    val fileId: Int,
    val downloaded: Long = 0,
    val prefix: Long = 0,
    val offset: Long = 0,
    val size: Long = 0,
    val path: String = "",
    val complete: Boolean = false,
    val error: String? = null,
) {
    val percent: Int get() = PlaybackFile.progressPercent(if (complete) size.coerceAtLeast(downloaded) else downloaded, size)
    val rotulo: String get() = error ?: if (complete) "Pronto" else PlaybackFile.rotuloProgresso(downloaded, size)
}

/** Acompanha o download progressivo do TDLib (UpdateFile) para o player. */
object TgStream {
    private val map = MutableStateFlow<Map<Int, StreamProgress>>(emptyMap())
    fun of(fileId: Int): StreamProgress? = map.value[fileId]
    fun flow(): StateFlow<Map<Int, StreamProgress>> = map

    fun update(f: TdApi.File) {
        if (f.id <= 0) return
        val loc = f.local
        val size = PlaybackFile.resolvedSize(f.size, f.expectedSize, 0)
        map.update { cur ->
            cur + (f.id to StreamProgress(
                fileId = f.id,
                downloaded = loc?.downloadedSize ?: 0L,
                prefix = loc?.downloadedPrefixSize ?: 0L,
                offset = loc?.downloadOffset ?: 0L,
                size = size,
                path = loc?.path ?: "",
                complete = loc?.isDownloadingCompleted == true,
            ))
        }
    }

    fun error(fileId: Int, msg: String) {
        map.update { cur ->
            val prev = cur[fileId] ?: StreamProgress(fileId)
            cur + (fileId to prev.copy(error = msg))
        }
    }

    suspend fun refresh(fileId: Int): TdApi.File? = try {
        val f = Td.send(TdApi.GetFile(fileId), 15_000)
        update(f)
        f
    } catch (_: Exception) { null }

    /** Garante que [offset, offset+length) já está no disco (ou o arquivo inteiro acabou). */
    suspend fun waitForRange(fileId: Int, offset: Long, length: Long, timeoutMs: Long = 45_000): TdApi.File {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastErr: Exception? = null
        var kicked = false
        while (true) {
            val f = try { Td.send(TdApi.GetFile(fileId), 15_000) } catch (e: Exception) {
                lastErr = e
                if (System.currentTimeMillis() > deadline) throw e
                delay(120)
                continue
            }
            update(f)
            val loc = f.local
            val size = PlaybackFile.resolvedSize(f.size, f.expectedSize, 0)
            if (loc != null && loc.isDownloadingCompleted) return f
            val want = if (length <= 0L) 64 * 1024L else length
            if (loc != null && PlaybackFile.rangeAvailable(loc.downloadOffset, loc.downloadedPrefixSize, offset, want)) return f
            if (System.currentTimeMillis() > deadline) {
                val got = loc?.downloadedPrefixSize ?: 0L
                throw TdException(408, "Demorou demais para carregar o vídeo (${PlaybackFile.rotuloProgresso(got, size)}). Confira a internet e tente de novo.")
            }
            if (loc == null || !loc.isDownloadingActive || !kicked) {
                try { Td.send(TdApi.DownloadFile(fileId, 32, offset, 0, false), 15_000) } catch (e: Exception) { lastErr = e }
                kicked = true
            }
            delay(80)
        }
    }

    suspend fun start(fileId: Int, offset: Long = 0L, limit: Long = 0L): TdApi.File {
        val f = Td.send(TdApi.DownloadFile(fileId, 32, offset, limit, false), 20_000)
        update(f)
        return f
    }
}
