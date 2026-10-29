package br.geracaoai.nuvem.tg

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import kotlin.math.min

/** Lê vídeo direto do Telegram em partes (streaming), sem baixar o arquivo inteiro antes. */
@UnstableApi
class TdDataSource(private val fileId: Int, private val size: Long) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L

    override fun open(spec: DataSpec): Long {
        uri = spec.uri; pos = spec.position
        remaining = if (spec.length != C.LENGTH_UNSET.toLong()) spec.length else size - pos
        transferInitializing(spec)
        runBlocking { try { Td.send(TdApi.DownloadFile(fileId, 32, pos, 0, false)) } catch (_: Exception) {} }
        transferStarted(spec)
        return remaining
    }

    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val want = min(min(len.toLong(), remaining), 256 * 1024L)
        val data = runBlocking {
            try { Td.send(TdApi.ReadFilePart(fileId, pos, want)).data }
            catch (_: TdException) {
                Td.send(TdApi.DownloadFile(fileId, 32, pos, want, true))
                Td.send(TdApi.ReadFilePart(fileId, pos, want)).data
            }
        }
        if (data.isEmpty()) return C.RESULT_END_OF_INPUT
        System.arraycopy(data, 0, buf, off, data.size)
        pos += data.size; remaining -= data.size
        bytesTransferred(data.size)
        return data.size
    }

    override fun getUri() = uri
    override fun close() { if (uri != null) { uri = null; transferEnded() } }

    class Factory(private val fileId: Int, private val size: Long) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdDataSource(fileId, size)
    }
}
