package br.geracaoai.nuvem.data

/**
 * Visão de uma pasta: arquivos locais + itens só na nuvem (depois de liberar espaço).
 * A classificação usa o índice local (hash em cache e nome+tamanho). Não fala com o Telegram.
 */
enum class FolderItemKind { LOCAL_PENDING, LOCAL_BACKED, CLOUD_ONLY }

enum class FolderMediaFilter { ALL, ON_DEVICE, CLOUD_ONLY }

enum class FolderTypeFilter { ALL, PHOTOS, VIDEOS }

enum class FolderSort { DATE, SIZE }

data class FolderMediaItem(
    val id: String,
    val name: String,
    val size: Long,
    val taken: Long,
    val mime: String,
    val durationMs: Long = 0,
    val kind: FolderItemKind,
    val local: LocalItem? = null,
    val cloud: CloudItem? = null,
) {
    val isVideo: Boolean get() = mime.startsWith("video/") || (cloud?.isVideo == true)
    val selectable: Boolean get() = kind == FolderItemKind.LOCAL_BACKED && local != null
}

object FolderMedia {
    /** Pasta com backup ligado ou com algo já na nuvem. */
    fun hasBackup(included: Boolean, cloudCount: Int): Boolean = included || cloudCount > 0

    fun isBacked(hash: String?, name: String, size: Long, cloudByHashSize: Set<Pair<String, Long>>, cloudByNameSize: Set<Pair<String, Long>>): Boolean {
        if (!hash.isNullOrBlank() && (hash to size) in cloudByHashSize) return true
        // Sem hash em cache: nome+tamanho no índice já indica que subiu. Se o hash existe e não bate, o arquivo mudou.
        if (hash.isNullOrBlank() && name.isNotBlank() && (name to size) in cloudByNameSize) return true
        return false
    }

    fun merge(local: List<LocalItem>, cloud: List<CloudItem>, hashOf: (LocalItem) -> String?): List<FolderMediaItem> {
        val cloudByHashSize = cloud.filter { it.hash.isNotBlank() }.map { it.hash to it.size }.toSet()
        val cloudByNameSize = cloud.filter { it.name.isNotBlank() }.map { it.name to it.size }.toSet()
        val byPath = cloud.filter { it.path.isNotBlank() }.groupBy { it.path }
        val used = HashSet<String>()

        fun key(c: CloudItem) = "${c.chatId}:${c.msgId}"

        fun findCloud(item: LocalItem, hash: String?): CloudItem? {
            if (!hash.isNullOrBlank()) {
                cloud.firstOrNull { it.hash == hash && it.size == item.size }?.let { return it }
            }
            byPath[item.path]?.firstOrNull()?.let { return it }
            if (item.name.isNotBlank()) cloud.firstOrNull { it.name == item.name && it.size == item.size }?.let { return it }
            return null
        }

        val locals = local.map { item ->
            val hash = hashOf(item)
            val ci = findCloud(item, hash)
            val backed = isBacked(hash, item.name, item.size, cloudByHashSize, cloudByNameSize)
            if (ci != null) used += key(ci)
            FolderMediaItem(
                id = "local:${item.mediaId}:${item.path}",
                name = item.name,
                size = item.size,
                taken = item.taken,
                mime = item.mime,
                durationMs = item.durationMs,
                kind = if (backed) FolderItemKind.LOCAL_BACKED else FolderItemKind.LOCAL_PENDING,
                local = item,
                cloud = ci,
            )
        }

        val cloudOnly = cloud.filter { key(it) !in used }.map { ci ->
            FolderMediaItem(
                id = "cloud:${ci.chatId}:${ci.msgId}",
                name = ci.name,
                size = ci.size,
                taken = ci.taken,
                mime = ci.mime,
                kind = FolderItemKind.CLOUD_ONLY,
                cloud = ci,
            )
        }

        return (locals + cloudOnly).sortedWith(compareByDescending<FolderMediaItem> { it.taken }.thenBy { it.id })
    }

    fun applyFilter(items: List<FolderMediaItem>, filter: FolderMediaFilter): List<FolderMediaItem> = when (filter) {
        FolderMediaFilter.ALL -> items
        FolderMediaFilter.ON_DEVICE -> items.filter { it.kind != FolderItemKind.CLOUD_ONLY }
        FolderMediaFilter.CLOUD_ONLY -> items.filter { it.kind == FolderItemKind.CLOUD_ONLY }
    }

    fun selectableIds(items: List<FolderMediaItem>): Set<String> =
        items.filter { it.selectable }.map { it.id }.toSet()

    fun selectAll(items: List<FolderMediaItem>): Set<String> = selectableIds(items)

    fun deselectAll(): Set<String> = emptySet()

    fun toggle(selected: Set<String>, id: String, items: List<FolderMediaItem>): Set<String> {
        val item = items.firstOrNull { it.id == id } ?: return selected
        if (!item.selectable) return selected
        return if (id in selected) selected - id else selected + id
    }

    fun selectedLocals(items: List<FolderMediaItem>, selected: Set<String>): List<LocalItem> =
        items.filter { it.id in selected && it.selectable }.mapNotNull { it.local }

    fun summary(items: List<FolderMediaItem>, selected: Set<String>): Pair<Int, Long> {
        val chosen = items.filter { it.id in selected && it.selectable }
        return chosen.size to chosen.sumOf { it.size }
    }

    /** Só o que passou na verificação do Telegram pode ir pra lixeira. */
    fun allowedToTrash(selected: List<LocalItem>, verified: List<LocalItem>): List<LocalItem> {
        val ok = verified.map { it.path to it.size }.toSet()
        return selected.filter { (it.path to it.size) in ok }
    }

    fun skippedCount(selectedCount: Int, verifiedCount: Int): Int =
        (selectedCount - verifiedCount).coerceAtLeast(0)

    fun applyType(items: List<FolderMediaItem>, type: FolderTypeFilter): List<FolderMediaItem> = when (type) {
        FolderTypeFilter.ALL -> items
        FolderTypeFilter.PHOTOS -> items.filter { !it.isVideo }
        FolderTypeFilter.VIDEOS -> items.filter { it.isVideo }
    }

    fun applySort(items: List<FolderMediaItem>, sort: FolderSort): List<FolderMediaItem> = when (sort) {
        FolderSort.DATE -> items
        FolderSort.SIZE -> items.sortedWith(compareByDescending<FolderMediaItem> { it.size }.thenByDescending { it.taken })
    }

    fun restoreIds(items: List<FolderMediaItem>): Set<String> =
        items.filter { it.kind == FolderItemKind.CLOUD_ONLY && it.cloud != null }.map { it.id }.toSet()

    fun toggleAny(selected: Set<String>, id: String, items: List<FolderMediaItem>): Set<String> {
        val item = items.firstOrNull { it.id == id } ?: return selected
        if (item.kind == FolderItemKind.LOCAL_PENDING) return selected
        return if (id in selected) selected - id else selected + id
    }
}
