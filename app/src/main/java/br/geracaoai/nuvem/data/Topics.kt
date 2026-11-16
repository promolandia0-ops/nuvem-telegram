package br.geracaoai.nuvem.data

import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.tg.TdException
import org.drinkless.tdlib.TdApi
import org.json.JSONObject

/** Grupo com tópicos (forum) e mapeamento nome → id. */
object Topics {
    private val colors = intArrayOf(0x6FB9F0, 0xFFD67E, 0xCB86DB, 0x8EEE98, 0xFF93B2, 0xFB6F5F)

    fun isChannel(c: TdApi.Chat) = (c.type as? TdApi.ChatTypeSupergroup)?.isChannel == true
    fun isGroup(c: TdApi.Chat) = (c.type as? TdApi.ChatTypeSupergroup)?.let { !it.isChannel } == true
    fun topic(id: Int): TdApi.MessageTopic? = if (id != 0) TdApi.MessageTopicForum(id) else null

    suspend fun list(chat: Long): List<TdApi.ForumTopicInfo> {
        val out = ArrayList<TdApi.ForumTopicInfo>()
        var od = 0; var om = 0L; var ot = 0
        repeat(20) {
            val page = try { Td.send(TdApi.GetForumTopics(chat, "", od, om, ot, 100), 20_000) } catch (_: Exception) { return out }
            val topics = page.topics ?: return out
            if (topics.isEmpty()) return out
            topics.forEach { t -> t.info?.let { out += it } }
            od = page.nextOffsetDate; om = page.nextOffsetMessageId; ot = page.nextOffsetForumTopicId
        }
        return out
    }

    private fun cache(): MutableMap<String, Int> {
        val m = mutableMapOf<String, Int>()
        try {
            val j = JSONObject(Prefs.topicMap.ifBlank { "{}" })
            j.keys().forEach { k -> m[k] = j.optInt(k) }
        } catch (_: Exception) {}
        return m
    }
    private fun save(m: Map<String, Int>) {
        val j = JSONObject(); m.forEach { (k, v) -> if (v != 0) j.put(k, v) }; Prefs.topicMap = j.toString()
    }

    suspend fun idFor(chat: Long, name: String): Int {
        val key = name.ifBlank { "Outros" }
        if (chat == 0L) return 0
        val ck = "$chat|$key"
        val map = cache()
        map[ck]?.takeIf { it != 0 }?.let { return it }
        val all = try { list(chat) } catch (_: Exception) { emptyList() }
        if (all.isEmpty()) return 0
        all.firstOrNull { it.name.equals(key, true) }?.let {
            map[ck] = it.forumTopicId; save(map); return it.forumTopicId
        }
        return try {
            val info = Td.send(TdApi.CreateForumTopic().apply {
                chatId = chat; this.name = key
                icon = TdApi.ForumTopicIcon(colors[kotlin.math.abs(key.hashCode()) % colors.size], 0L)
            }, 20_000)
            map[ck] = info.forumTopicId; save(map); info.forumTopicId
        } catch (_: Exception) { 0 }
    }

    suspend fun generalId(chat: Long): Int =
        try { list(chat).firstOrNull { it.isGeneral }?.forumTopicId ?: 0 } catch (_: Exception) { 0 }

    /** Se o destino atual é um canal (sem tópicos), cria/abre um grupo fórum e guarda o canal antigo. */
    suspend fun ensureForum(chat: TdApi.Chat): TdApi.Chat {
        val type = chat.type as? TdApi.ChatTypeSupergroup ?: return chat
        if (type.isChannel) {
            val forum = try {
                Repo.findNuvemChats().firstOrNull { isGroup(it) && it.id != chat.id }
            } catch (_: Exception) { null } ?: Repo.createForum()
            if (forum.id != chat.id) {
                if (Prefs.legacyChatId == 0L) Prefs.legacyChatId = chat.id
                Repo.useChat(forum)
                try { Td.send(TdApi.OpenChat(forum.id), 15_000) } catch (_: Exception) {}
                try {
                    Td.send(TdApi.SendMessage().apply {
                        chatId = chat.id
                        inputMessageContent = TdApi.InputMessageText().apply {
                            text = TdApi.FormattedText("Os backups novos do app Nuvem passam a ir no grupo com tópicos “Nuvem”. Este canal fica como arquivo.", emptyArray())
                        }
                        options = TdApi.MessageSendOptions().apply { disableNotification = true }
                    }, 20_000)
                } catch (_: Exception) {}
            }
            return forum
        }
        try {
            val sg = Td.send(TdApi.GetSupergroup(type.supergroupId), 15_000)
            if (!sg.isForum) {
                try { Td.send(TdApi.ToggleSupergroupIsForum(type.supergroupId, true, true), 20_000) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return try { Td.send(TdApi.GetChat(chat.id), 15_000) } catch (_: Exception) { chat }
    }
}
