package br.geracaoai.nuvem.data

import android.content.Context
import java.io.File

/**
 * Guarda o hash de cada arquivo, pela chave caminho + tamanho + data de modificação,
 * para não ler o arquivo inteiro de novo em todo backup.
 */
object HashCache {
    private val map = HashMap<String, String>()
    private var file: File? = null

    fun load(c: Context) {
        val f = File(c.filesDir, "hashes.txt")
        file = f
        map.clear()
        try {
            if (f.exists()) {
                f.forEachLine { line ->
                    val p = line.split('\t')
                    if (p.size == 4) map["${p[0]}\t${p[1]}\t${p[2]}"] = p[3]
                }
            }
        } catch (_: Exception) {}
    }

    fun get(path: String, size: Long, mtime: Long): String? = map["$path\t$size\t$mtime"]

    fun put(path: String, size: Long, mtime: Long, hash: String) {
        map["$path\t$size\t$mtime"] = hash
    }

    fun save() {
        val f = file ?: return
        try {
            f.bufferedWriter().use { w ->
                for ((k, v) in map) {
                    w.write(k)
                    w.write("\t")
                    w.write(v)
                    w.write("\n")
                }
            }
        } catch (_: Exception) {}
    }
}
