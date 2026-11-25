package br.geracaoai.nuvem.data

import java.util.Locale

/**
 * Ordem e agrupamento do backup: uma pasta do celular de cada vez, igual à galeria.
 * Sem categorias (prints, memes, vídeos longos). O nome do álbum é o da pasta.
 */
object FolderPlan {
    fun albumName(bucket: String): String = if (FileScan.isKey(bucket)) FileScan.nameOf(bucket) else bucket.ifBlank { "Outros" }

    fun isCameraFolder(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val low = n.lowercase(Locale.ROOT)
        if (low == "dcim" || low == "camera" || low == "câmera" || low == "camera2" || low == "câmera2") return true
        return Classify.isCamera(n, n)
    }

    /** Câmera primeiro; o resto em ordem alfabética, estável. */
    fun order(folders: Collection<String>): List<String> {
        val list = folders.distinct()
        val cam = list.filter { isCameraFolder(it) }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        val rest = list.filter { !isCameraFolder(it) }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        return cam + rest
    }

    /**
     * Tira do buffer os grupos prontos para enviar (até [max] itens, sem misturar mídia e documento).
     * Se [flush] é falso, o resto incompleto fica no buffer. A ordem dos itens é preservada.
     */
    fun <T> pullReadyChunks(
        buffer: MutableList<T>,
        flush: Boolean,
        isDoc: (T) -> Boolean,
        max: Int = 10,
    ): List<List<T>> {
        val out = ArrayList<List<T>>()
        val src = buffer.toList()
        buffer.clear()
        val cur = ArrayList<T>()
        var curDoc: Boolean? = null
        for (item in src) {
            val d = isDoc(item)
            if (cur.isNotEmpty() && curDoc != null && d != curDoc) {
                out += cur.toList()
                cur.clear()
            }
            curDoc = d
            cur += item
            if (cur.size == max) {
                out += cur.toList()
                cur.clear()
            }
        }
        if (flush) {
            if (cur.isNotEmpty()) out += cur.toList()
        } else {
            buffer.addAll(cur)
        }
        return out
    }
}
