package br.geracaoai.nuvem.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

object DiscoverStore {
    val scanning = MutableStateFlow(false)
    val summary = MutableStateFlow<DiscoverSummary?>(null)

    suspend fun refresh(c: Context, biggestFirst: Boolean = false): DiscoverSummary = withContext(Dispatchers.IO) {
        scanning.value = true
        try {
            val s = scan(c, biggestFirst)
            summary.value = s
            s
        } catch (e: Exception) {
            val s = DiscoverSummary(error = e.message ?: "Falha ao procurar arquivos")
            summary.value = s
            s
        } finally {
            scanning.value = false
        }
    }

    fun scan(c: Context, biggestFirst: Boolean = false): DiscoverSummary {
        val folders = (Prefs.folders + Prefs.secretFolders).filter { it.isNotBlank() }
        val access = AllFilesAccess.granted()
        if (folders.isEmpty()) return DiscoverSummary(allFilesAccess = access)
        val cloud = try { Db.allCloudMany(Prefs.allKnownChats()) } catch (_: Exception) { emptyList() }
        val index = cloud.map { CloudIdentity(it.path, it.name, it.size, it.hash) }
        val diags = ArrayList<FolderDiag>()
        val allNew = ArrayList<LocalItem>()
        val allFree = ArrayList<LocalItem>()
        val seenNew = HashSet<String>()
        for (b in folders) {
            val items = try { LocalMedia.scanAndWalk(c, b) } catch (_: Exception) { emptyList() }
            val listed = with(LocalMedia) { items.map { it.toListed() } }
            val pending = DiscoverLogic.pendingFast(listed, index)
            val free = DiscoverLogic.freeableFast(listed, index)
            val paths = (LocalMedia.folderDirs(b) + FolderAccess.findDirsNamed(b)).map { it.absolutePath }.distinct()
            val nomedia = paths.any { File(it, ".nomedia").isFile }
            val unread = listed.count { !it.readable }
            val newItems = with(LocalMedia) { pending.map { it.toLocalItem() } }
            val freeItems = with(LocalMedia) { free.map { it.toLocalItem() } }
            for (i in newItems) {
                val k = BackupDiff.normalizePath(i.path)
                if (k in seenNew) continue
                seenNew += k
                allNew += i
            }
            allFree += freeItems
            val cloudIn = cloud.count { DiscoverLogic.normalizeAlbum(it.folder, it.path) == b || it.folder == b }
            val note = when {
                items.isEmpty() && !access && paths.isEmpty() -> "Não achei a pasta no disco. Liberar “Acesso a todos os arquivos” ou apontar a pasta em Ajustes › Diagnóstico."
                items.isEmpty() && paths.isNotEmpty() && unread == listed.size && listed.isNotEmpty() -> "Vi a pasta, mas sem permissão de leitura."
                items.isEmpty() -> "Nenhum arquivo de foto ou vídeo nesta pasta."
                nomedia && pending.isNotEmpty() -> "Pasta com .nomedia: o app leu pelo disco."
                else -> ""
            }
            diags += FolderDiag(
                folder = b, paths = paths, diskCount = items.size, diskBytes = items.sumOf { it.size },
                cloudCount = cloudIn, newCount = pending.size, newBytes = pending.sumOf { it.size },
                freeableCount = free.size, freeableBytes = free.sumOf { it.size },
                unreadable = unread, hasNomedia = nomedia, note = note,
            )
        }
        val news = if (biggestFirst) allNew.sortedByDescending { it.size } else allNew
        return DiscoverSummary(diags, news, allFree.distinctBy { BackupDiff.normalizePath(it.path) }, access)
    }
}
