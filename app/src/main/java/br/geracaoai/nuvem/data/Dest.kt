package br.geracaoai.nuvem.data

import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.tg.TdException
import org.drinkless.tdlib.TdApi

object DestKind {
    const val GROUP = "group"
    const val FORUM = "forum"
    const val CHANNEL = "channel"
    const val SAVED = "saved"
    val all = listOf(GROUP, FORUM, CHANNEL, SAVED)
    fun label(k: String) = when (k) {
        FORUM -> "Grupo com tópicos"
        CHANNEL -> "Canal"
        SAVED -> "Mensagens salvas"
        else -> "Grupo"
    }
    fun hint(k: String) = when (k) {
        FORUM -> "Grupo privado, com um tópico por pasta da galeria"
        CHANNEL -> "Canal privado só seu"
        SAVED -> "Fica nas suas Mensagens Salvas do Telegram"
        else -> "Grupo privado só seu, sem tópicos"
    }
    fun default() = GROUP
    fun effective(): String = Prefs.destKind.ifBlank { default() }
}

object Dest {
    fun hasExistingBackup(): Boolean = try {
        Prefs.allKnownChats().isNotEmpty() || Prefs.legacyChatId != 0L ||
            Prefs.galleryChats().isNotEmpty() || Db.allCloudMany(Prefs.allKnownChats()).isNotEmpty()
    } catch (_: Exception) { Prefs.allKnownChats().isNotEmpty() }

    /** Prepara o destino já escolhido, sem trocar canal por grupo sozinho. */
    suspend fun prepare(chat: TdApi.Chat): TdApi.Chat {
        val kind = DestKind.effective()
        val type = chat.type as? TdApi.ChatTypeSupergroup ?: return chat
        if (kind == DestKind.FORUM && !type.isChannel) {
            try {
                val sg = Td.send(TdApi.GetSupergroup(type.supergroupId), 15_000)
                if (!sg.isForum) Td.send(TdApi.ToggleSupergroupIsForum(type.supergroupId, true, true), 20_000)
            } catch (_: Exception) {}
            return try { Td.send(TdApi.GetChat(chat.id), 15_000) } catch (_: Exception) { chat }
        }
        return chat
    }
}
