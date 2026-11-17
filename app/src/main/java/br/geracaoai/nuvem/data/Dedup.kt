package br.geracaoai.nuvem.data

/**
 * Detecta cópias repetidas (fotos, vídeos e documentos/originais) e decide o que manter.
 * Chave, nesta ordem: hash do conteúdo, unique id do Telegram + tamanho, nome+tamanho.
 * Fica a cópia mais antiga e, se houver, um documento-original do mesmo arquivo.
 */
data class DupItem(
    val msgId: Long,
    val chatId: Long,
    val name: String,
    val mime: String,
    val size: Long,
    val taken: Long,
    val date: Int,
    val folder: String,
    val hash: String,
    val uniqueId: String,
    val isOriginal: Boolean,
) {
    val isVideo get() = mime.startsWith("video/")
    val isPhoto get() = mime.startsWith("image/")
    val isDoc get() = !isVideo && !isPhoto
}

data class DupPlan(
    val keep: List<DupItem>,
    val delete: List<DupItem>,
) {
    val deleteCount get() = delete.size
    val deleteBytes get() = delete.sumOf { it.size.coerceAtLeast(0) }
    val deletePhotos get() = delete.count { it.isPhoto && !it.isOriginal }
    val deleteVideos get() = delete.count { it.isVideo }
    val deleteDocs get() = delete.count { it.isDoc || it.isOriginal }
    val preview: String get() {
        if (delete.isEmpty()) return "Nenhuma duplicada encontrada."
        val parts = ArrayList<String>()
        if (deletePhotos > 0) parts += "$deletePhotos foto" + if (deletePhotos == 1) "" else "s"
        if (deleteVideos > 0) parts += "$deleteVideos vídeo" + if (deleteVideos == 1) "" else "s"
        if (deleteDocs > 0) parts += "$deleteDocs arquivo" + if (deleteDocs == 1) "" else "s"
        val what = if (parts.isEmpty()) "$deleteCount item" + if (deleteCount == 1) "" else "s" else parts.joinToString(", ")
        return "$what duplicados • ${formatSize(deleteBytes)}"
    }
}

object Dedup {
    fun groupKey(item: DupItem): String? {
        if (item.hash.isNotBlank()) return "h:${item.hash}"
        if (item.uniqueId.isNotBlank()) return "u:${item.uniqueId}:${item.size}"
        val n = item.name.trim()
        if (n.isNotBlank() && n != "item" && item.size > 0) return "n:${n.lowercase()}:${item.size}"
        return null
    }

    /** Mais antigo primeiro (data da mensagem, depois data da mídia, depois id). */
    fun olderFirst(a: DupItem, b: DupItem): Int {
        val byDate = a.date.compareTo(b.date)
        if (byDate != 0) return byDate
        val ta = if (a.taken > 0) a.taken else Long.MAX_VALUE
        val tb = if (b.taken > 0) b.taken else Long.MAX_VALUE
        val byTaken = ta.compareTo(tb)
        if (byTaken != 0) return byTaken
        return a.msgId.compareTo(b.msgId)
    }

    fun plan(items: List<DupItem>): DupPlan {
        val keep = ArrayList<DupItem>()
        val delete = ArrayList<DupItem>()
        val groups = LinkedHashMap<String, ArrayList<DupItem>>()
        for (i in items) {
            val k = groupKey(i)
            if (k == null) { keep += i; continue }
            groups.getOrPut(k) { ArrayList() } += i
        }
        for ((_, g) in groups) {
            if (g.size == 1) { keep += g[0]; continue }
            val visible = g.filter { !it.isOriginal }.sortedWith(::olderFirst)
            val originals = g.filter { it.isOriginal }.sortedWith(::olderFirst)
            if (visible.isNotEmpty()) {
                keep += visible[0]
                delete += visible.drop(1)
                if (originals.isNotEmpty()) {
                    // Prefer the original that shares the kept item's hash, else the oldest.
                    val pair = originals.firstOrNull { it.hash.isNotBlank() && it.hash == visible[0].hash } ?: originals[0]
                    keep += pair
                    delete += originals.filter { it.msgId != pair.msgId || it.chatId != pair.chatId }
                }
            } else {
                keep += originals[0]
                delete += originals.drop(1)
            }
        }
        return DupPlan(keep, delete)
    }

    /** Evita enfileirar o mesmo hash duas vezes no mesmo backup (ex.: foto na Câmera e no WhatsApp). */
    fun claimHash(seen: MutableSet<String>, hash: String, size: Long): Boolean {
        if (hash.isBlank()) return true
        val k = "$hash:$size"
        if (k in seen) return false
        seen += k
        return true
    }
}
