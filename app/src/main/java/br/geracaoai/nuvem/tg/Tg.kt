package br.geracaoai.nuvem.tg

import br.geracaoai.nuvem.data.Meta
import br.geracaoai.nuvem.data.Prefs
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import java.io.File

/** Uma mensagem da nuvem que traz a legenda #nuvem. */
data class CloudRec(val chatId: Long, val msgId: Long, val meta: Meta)

/** Onde está um arquivo da nuvem: id para baixar, caminho já baixado (se houver) e miniatura. */
class MediaRef(val fileId: Int, val localPath: String, val thumbFileId: Int?, val mini: ByteArray?)

/** Operações com o Telegram usadas pelo backup. */
object Tg {
    private const val UPLOAD_TIMEOUT_MS = 30L * 60_000L

    /** Sem destino salvo, usa as Mensagens Salvas da própria conta. */
    suspend fun ensureDestination(): Long {
        if (Prefs.chatId != 0L) return Prefs.chatId
        val me = Td.send(TdApi.GetMe(), 20_000)
        val chat = Td.send(TdApi.CreatePrivateChat(me.id, false), 20_000)
        Prefs.chatId = chat.id
        return chat.id
    }

    fun captionOf(c: TdApi.MessageContent?): String? = when (c) {
        is TdApi.MessageDocument -> c.caption?.text
        is TdApi.MessagePhoto -> c.caption?.text
        is TdApi.MessageVideo -> c.caption?.text
        is TdApi.MessageAnimation -> c.caption?.text
        is TdApi.MessageAudio -> c.caption?.text
        else -> null
    }

    /** Lê o histórico de cada destino, do mais novo para o mais antigo, e junta o que tem legenda #nuvem. */
    suspend fun scanCloud(chats: List<Long>): List<CloudRec> {
        val out = ArrayList<CloudRec>()
        for (chat in chats) {
            var from = 0L
            while (true) {
                val r = Td.send(TdApi.GetChatHistory().apply {
                    chatId = chat
                    fromMessageId = from
                    offset = 0
                    limit = 100
                    onlyLocal = false
                }, 30_000)
                val msgs = (r.messages ?: emptyArray()).filterNotNull()
                if (msgs.isEmpty()) break
                for (m in msgs) {
                    val meta = Meta.parse(captionOf(m.content)) ?: continue
                    out += CloudRec(chat, m.id, meta)
                }
                val oldest = msgs.minOf { it.id }
                if (oldest == from) break
                from = oldest
            }
        }
        return out
    }

    /** Sobe um arquivo como documento (sem compressão) e espera o Telegram confirmar. */
    suspend fun upload(chat: Long, file: File, texto: String): Long {
        val conteudo = TdApi.InputMessageDocument().apply {
            document = TdApi.InputDocument().apply {
                document = TdApi.InputFileLocal(file.absolutePath)
                disableContentTypeDetection = true
            }
            this.caption = TdApi.FormattedText(texto, emptyArray())
        }
        val tmp = Td.send(TdApi.SendMessage().apply {
            chatId = chat
            inputMessageContent = conteudo
            options = TdApi.MessageSendOptions().apply { disableNotification = true }
        }, 60_000)
        val res = try {
            withTimeout(UPLOAD_TIMEOUT_MS) { Td.awaitSend(tmp.id) }
        } catch (e: TimeoutCancellationException) {
            throw TdException(408, "O envio demorou demais. Vou tentar de novo.")
        }
        res.error?.let { throw TdException(it.code, it.message ?: "erro no envio") }
        return res.message?.id ?: throw TdException(0, "O Telegram não confirmou o envio.")
    }

    /** Apaga mensagens da nuvem (somem do Telegram para todos os lados). */
    suspend fun deleteMessages(chat: Long, ids: List<Long>) {
        for (parte in ids.chunked(100)) {
            Td.send(TdApi.DeleteMessages(chat, parte.toLongArray(), true), 60_000)
        }
    }

    /** Onde está o arquivo de uma mensagem: id para baixar, caminho já baixado e miniatura. */
    suspend fun mediaOf(chat: Long, msgId: Long): MediaRef? {
        val m = Td.send(TdApi.GetMessage(chat, msgId), 30_000L)
        return when (val c = m.content) {
            is TdApi.MessageDocument -> ref(c.document.document, c.document.thumbnail?.file, c.document.minithumbnail?.data)
            is TdApi.MessageVideo -> ref(c.video.video, c.video.thumbnail?.file, c.video.minithumbnail?.data)
            is TdApi.MessageAnimation -> ref(c.animation.animation, c.animation.thumbnail?.file, c.animation.minithumbnail?.data)
            is TdApi.MessagePhoto -> {
                val grande = c.photo.sizes.maxByOrNull { it.width.toLong() * it.height }?.photo
                val pequena = c.photo.sizes.minByOrNull { it.width.toLong() * it.height }?.photo
                ref(grande, pequena, c.photo.minithumbnail?.data)
            }
            else -> null
        }
    }

    /** Baixa o arquivo inteiro do Telegram e devolve o caminho local, ou null se não terminou. */
    suspend fun download(fileId: Int, priority: Int): String? {
        val f = Td.send(TdApi.DownloadFile(fileId, priority, 0L, 0L, true), 0L)
        val l = f.local ?: return null
        return if (l.isDownloadingCompleted && l.path.isNotBlank()) l.path else null
    }

    private fun ref(file: TdApi.File?, thumb: TdApi.File?, mini: ByteArray?): MediaRef? {
        if (file == null) return null
        val local = file.local?.takeIf { it.isDownloadingCompleted }?.path ?: ""
        return MediaRef(file.id, local, thumb?.id, mini)
    }
}
