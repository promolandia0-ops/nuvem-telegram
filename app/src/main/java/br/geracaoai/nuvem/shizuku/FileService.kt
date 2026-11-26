package br.geracaoai.nuvem.shizuku

import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.system.exitProcess

/** Roda no processo do Shizuku (uid shell), que consegue ler Android/data de outros apps. */
class FileService : IFileService.Stub() {
    override fun destroy() { exitProcess(0) }

    override fun list(root: String, minSize: Long): MutableList<String> {
        val out = ArrayList<String>()
        val r = File(root); if (!r.exists()) return out
        r.walkTopDown().maxDepth(8).onEnter { d -> !d.name.equals("Stickers", true) && !d.name.startsWith(".thumb") }
            .forEach { f -> if (f.isFile && f.length() >= minSize && out.size < 5000) out += "${f.length()}|${f.lastModified()}|${f.absolutePath}" }
        return out
    }

    private var lastKey = ""
    private var lastList: List<String> = emptyList()

    override fun scan(root: String, minSize: Long, exts: String, offset: Int, limit: Int): MutableList<String> {
        val key = "$root|$minSize|$exts"
        if (key != lastKey || offset == 0) {
            val want = exts.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            val skip = setOf("cache", ".cache", "code_cache", "thumbnails", ".thumbnails", ".thumbs", "thumbs", "stickers", "emoji", "glide", "image_manager_disk_cache", "exo", "exoplayer", "temp", "tmp", "logs")
            val out = ArrayList<String>()
            val r = File(root)
            if (r.exists()) r.walkTopDown().maxDepth(12)
                .onEnter { d -> d == r || d.name.lowercase() !in skip && !d.name.startsWith(".thumb") }
                .forEach { f ->
                    if (!f.isFile) return@forEach
                    val len = f.length(); if (len < minSize) return@forEach
                    val n = f.name.lowercase()
                    if (n.startsWith(".thumbdata")) return@forEach
                    if (want.isNotEmpty() && n.substringAfterLast('.', "") !in want) return@forEach
                    out += "$len|${f.lastModified()}|${f.absolutePath}"
                }
            lastKey = key; lastList = out
        }
        val from = offset.coerceIn(0, lastList.size)
        return ArrayList(lastList.subList(from, (from + limit).coerceAtMost(lastList.size)))
    }

    override fun open(path: String): ParcelFileDescriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun remove(path: String): Boolean = File(path).delete()
}
