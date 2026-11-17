package br.geracaoai.nuvem.tg

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import br.geracaoai.nuvem.data.PlaybackFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * DataSource do ExoPlayer que lê o arquivo do Telegram em partes.
 * Se o usuário pular (seek), baixa a partir do novo offset.
 * Prefere o arquivo local quando o trecho já chegou; senão usa ReadFilePart.
 */
@UnstableApi
class TdDataSource(private val fileId: Int, private val knownSize: Long) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L
    private var size = knownSize
    private var raf: RandomAccessFile? = null
    private var localPath: String = ""
    private var localOffset = 0L
    private var localPrefix = 0L

    override fun open(spec: DataSpec): Long {
        uri = spec.uri
        pos = spec.position
        closeRaf()
        val f = block { TgStream.waitForRange(fileId, pos, (spec.length.takeIf { it != C.LENGTH_UNSET.toLong() } ?: 64 * 1024L), 60_000) }
        size = PlaybackFile.resolvedSize(f.size, f.expectedSize, knownSize)
        val loc = f.local
        localPath = loc?.path ?: ""
        localOffset = loc?.downloadOffset ?: 0L
        localPrefix = loc?.downloadedPrefixSize ?: 0L
        if (localPath.isNotBlank()) {
            try { raf = RandomAccessFile(localPath, "r") } catch (_: Exception) { raf = null }
        }
        remaining = if (spec.length != C.LENGTH_UNSET.toLong()) spec.length
        else if (size > pos) size - pos
        else C.LENGTH_UNSET.toLong()
        transferInitializing(spec)
        transferStarted(spec)
        return if (remaining == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else remaining
    }

    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val cap = if (remaining > 0L && remaining != C.LENGTH_UNSET.toLong()) min(len.toLong(), remaining) else len.toLong()
        val want = min(cap, 256 * 1024L).toInt()
        if (!rangeReady(pos, want.toLong())) {
            val f = block { TgStream.waitForRange(fileId, pos, want.toLong(), 45_000) }
            localPath = f.local?.path ?: localPath
            localOffset = f.local?.downloadOffset ?: localOffset
            localPrefix = f.local?.downloadedPrefixSize ?: localPrefix
            size = PlaybackFile.resolvedSize(f.size, f.expectedSize, size)
            if (raf == null && localPath.isNotBlank()) {
                try { raf = RandomAccessFile(localPath, "r") } catch (_: Exception) {}
            }
        }
        val n = readLocal(buf, off, want) ?: readPart(buf, off, want)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n
        if (remaining > 0L && remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    private fun rangeReady(offset: Long, len: Long): Boolean =
        PlaybackFile.rangeAvailable(localOffset, localPrefix, offset, len)

    private fun readLocal(buf: ByteArray, off: Int, want: Int): Int? {
        val f = raf ?: return null
        if (!rangeReady(pos, want.toLong())) return null
        return try {
            f.seek(pos)
            val n = f.read(buf, off, want)
            if (n > 0) n else null
        } catch (_: Exception) { null }
    }

    private fun readPart(buf: ByteArray, off: Int, want: Int): Int {
        val data = block {
            try {
                Td.send(TdApi.ReadFilePart(fileId, pos, want.toLong()), 30_000).data
            } catch (e: TdException) {
                Td.send(TdApi.DownloadFile(fileId, 32, pos, want.toLong(), true), 90_000)
                Td.send(TdApi.ReadFilePart(fileId, pos, want.toLong()), 30_000).data
            }
        }
        if (data == null || data.isEmpty()) return 0
        System.arraycopy(data, 0, buf, off, data.size)
        return data.size
    }

    override fun getUri() = uri

    override fun close() {
        closeRaf()
        if (uri != null) { uri = null; transferEnded() }
    }

    private fun closeRaf() { try { raf?.close() } catch (_: Exception) {}; raf = null }

    private fun <T> block(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    class Factory(private val fileId: Int, private val size: Long) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdDataSource(fileId, size)
    }
}
