package br.geracaoai.nuvem.data

import java.util.Locale

/**
 * Descoberta rápida do que falta no backup e agrupamento de álbuns da nuvem.
 * Comparação inicial é por caminho+tamanho, sem hash (hash só na hora de enviar ou liberar).
 */
object DiscoverLogic {
    const val OUTROS = "Outros"
    private val skipDirs = setOf(
        "android", ".thumbnails", ".trashed", ".trash", "lost+found", "obb",
        ".cache", ".thumbcache", "thumbs",
    )
    private val storageNoise = setOf("0", "emulated", "storage", "sdcard", "mnt", "media")

    fun shouldEnterDir(name: String, isWalkRoot: Boolean = false): Boolean {
        if (isWalkRoot) return true
        val n = name.lowercase(Locale.ROOT)
        if (n in skipDirs) return false
        return true
    }

    fun shouldEnterChild(parentName: String, childName: String, isWalkRoot: Boolean = false): Boolean {
        if (parentName.equals("Android", true) && (childName.equals("data", true) || childName.equals("obb", true))) return false
        return shouldEnterDir(childName, isWalkRoot)
    }

    /** Pasta com .nomedia entra na varredura: o MediaStore não indexa, o disco sim. */
    fun walkNomediaFolders(): Boolean = true

    fun pendingFast(files: List<ListedFile>, index: List<CloudIdentity>): List<ListedFile> =
        files.filter {
            it.size > 0L && BackupDiff.isMediaFile(it.name, it.mime) &&
                !BackupDiff.isUploaded(it.path, it.size, null, index)
        }

    fun freeableFast(files: List<ListedFile>, index: List<CloudIdentity>): List<ListedFile> =
        files.filter {
            it.size > 0L && BackupDiff.isMediaFile(it.name, it.mime) &&
                BackupDiff.isUploaded(it.path, it.size, null, index)
        }

    fun albumFromPath(path: String): String {
        val p = BackupDiff.normalizePath(path)
        val cut = p.substringBeforeLast('/', "")
        val parts = cut.split('/').map { it.trim() }.filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in storageNoise }
        return parts.lastOrNull().orEmpty()
    }

    fun normalizeAlbum(folder: String, path: String = ""): String {
        if (folder == ORIGINALS_FOLDER || folder == WATCHED_FOLDER) return folder
        val raw = folder.trim().replace('\\', '/').trim('/')
        val fromFolder = when {
            raw.isBlank() -> ""
            raw.contains('/') -> raw.substringAfterLast('/').ifBlank { raw }
            else -> raw
        }
        if (fromFolder.isNotBlank()) return fromFolder
        return albumFromPath(path).ifBlank { OUTROS }
    }

    data class AlbumGroup(
        val name: String,
        val count: Int,
        val bytes: Long,
        val items: List<CloudItem>,
    )

    /** Todas as pastas que têm algum item no índice do Telegram, inclusive já apagadas do celular. */
    fun groupAlbums(items: List<CloudItem>): List<AlbumGroup> {
        val visible = items.filter { it.folder != ORIGINALS_FOLDER && it.folder != WATCHED_FOLDER }
        return visible.groupBy { normalizeAlbum(it.folder, it.path) }
            .map { (name, list) ->
                AlbumGroup(name, list.size, list.sumOf { it.size.coerceAtLeast(0L) }, list.sortedByDescending { it.taken })
            }
            .sortedWith(compareByDescending<AlbumGroup> { it.bytes }.thenBy { it.name.lowercase(Locale.ROOT) })
    }
}

data class FolderDiag(
    val folder: String,
    val paths: List<String>,
    val diskCount: Int,
    val diskBytes: Long,
    val cloudCount: Int,
    val newCount: Int,
    val newBytes: Long,
    val freeableCount: Int,
    val freeableBytes: Long,
    val unreadable: Int,
    val hasNomedia: Boolean,
    val note: String,
)

data class DiscoverSummary(
    val folders: List<FolderDiag> = emptyList(),
    val newFiles: List<LocalItem> = emptyList(),
    val freeable: List<LocalItem> = emptyList(),
    val allFilesAccess: Boolean = false,
    val error: String = "",
) {
    val newCount get() = newFiles.size
    val newBytes get() = newFiles.sumOf { it.size }
    val freeableCount get() = freeable.size
    val freeableBytes get() = freeable.sumOf { it.size }
}
