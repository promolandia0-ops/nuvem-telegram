package br.geracaoai.nuvem.data

/**
 * Máquina de estados pura da leitura do histórico (sem TDLib).
 * Usada pelo syncIndex e testada no caminho de canal novo/vazio.
 */
object ChatHistoryWalk {
    const val MAX_EMPTY_ROUNDS = 2
    const val MAX_PAGES = 500

    data class Step(val nextFromId: Long, val emptyRounds: Int, val done: Boolean, val pages: Int)

    /**
     * @param fromId id usado nesta página (0 = do mais recente)
     * @param emptyRounds vazios seguidos até agora
     * @param messageIds ids desta página, do mais novo para o mais antigo (ordem do GetChatHistory)
     * @param full se true, percorre tudo; se false, para quando todos os ids já estão no índice
     * @param knownCount quantos ids desta página já existiam no banco
     * @param pages páginas já lidas, inclusive esta
     */
    fun next(
        fromId: Long,
        emptyRounds: Int,
        messageIds: LongArray,
        full: Boolean,
        knownCount: Int,
        pages: Int,
        maxEmptyRounds: Int = MAX_EMPTY_ROUNDS,
        maxPages: Int = MAX_PAGES,
    ): Step {
        val page = pages + 1
        if (page > maxPages) return Step(fromId, emptyRounds, done = true, pages = page)
        if (messageIds.isEmpty()) {
            val empty = emptyRounds + 1
            return Step(fromId, empty, done = empty >= maxEmptyRounds, pages = page)
        }
        val allKnown = !full && knownCount == messageIds.size && messageIds.isNotEmpty()
        return Step(messageIds.last(), 0, done = allKnown, pages = page)
    }

    fun isMissingChat(code: Int, message: String?): Boolean {
        val m = message?.uppercase() ?: ""
        return code == 400 && ("CHAT_ID_INVALID" in m || "CHAT_NOT_FOUND" in m || "CHAT NOT FOUND" in m)
    }
}
