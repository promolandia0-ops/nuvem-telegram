package br.geracaoai.nuvem.tg

import br.geracaoai.nuvem.data.Meta
import br.geracaoai.nuvem.data.Prefs
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import java.io.File

/** Uma mensagem da nuvem que traz a legenda #nuvem. */
data class CloudRec(val chatId: Long, val msgId: Long, val meta: Meta)

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
    suspend fun upload(chat: Long, file: File, texto: String) {
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
        if (res.message == null) throw TdException(0, "O Telegram não confirmou o envio.")
    }

    /** Apaga mensagens da nuvem (somem do Telegram para todos os lados). */
    suspend fun deleteMessages(chat: Long, ids: List<Long>) {
        for (parte in ids.chunked(100)) {
            Td.send(TdApi.DeleteMessages(chat, parte.toLongArray(), true), 60_000)
        }
    }
}
