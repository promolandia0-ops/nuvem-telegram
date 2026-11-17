package br.geracaoai.nuvem.data

/** Diferença entre o índice local e as mensagens que ainda existem no Telegram. */
object CloudSync {
    fun missingIds(stored: Collection<Long>, found: Collection<Long>): List<Long> {
        if (stored.isEmpty()) return emptyList()
        val have = if (found is Set<Long>) found else found.toHashSet()
        return stored.filter { it !in have }
    }

    fun foundIds(ids: Iterable<Long?>): Set<Long> =
        ids.mapNotNull { id -> id?.takeIf { it != 0L } }.toHashSet()

    fun isMessageGone(code: Int, message: String?): Boolean {
        if (code == 404) return true
        val u = (message ?: "").uppercase()
        return "MESSAGE_NOT_FOUND" in u || "MSG_ID_INVALID" in u || "MESSAGE_ID_INVALID" in u ||
            "MESSAGE_NOT_MODIFIED" in u && "NOT_FOUND" in u
    }

    fun isChatGone(code: Int, message: String?): Boolean {
        if (ChatHistoryWalk.isMissingChat(code, message)) return true
        val u = (message ?: "").uppercase()
        return "CHAT_NOT_FOUND" in u || "CHAT_ID_INVALID" in u
    }
}
