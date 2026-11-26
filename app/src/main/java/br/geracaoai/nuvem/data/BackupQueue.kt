package br.geracaoai.nuvem.data

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Fila única do backup: o que você escolheu na mão (álbuns do celular, pastas e arquivos).
 * Tudo sobe na mesma rodada do backup, junto com o automático. Escolher mais enquanto roda
 * só aumenta a fila; o backup emenda quando terminar a rodada atual.
 * Entradas: "bucket:<álbum>", "dir:<caminho>", "file:<caminho>".
 */
object BackupQueue {
    private const val KEY = "backup_queue"
    val items = MutableStateFlow<Set<String>>(emptySet())

    fun load() { items.value = try { Prefs.raw.getStringSet(KEY, emptySet())?.toSet() ?: emptySet() } catch (_: Exception) { emptySet() } }
    private fun save(v: Set<String>) { items.value = v; try { Prefs.raw.edit().putStringSet(KEY, v).apply() } catch (_: Exception) {} }

    fun current(): Set<String> { if (items.value.isEmpty()) load(); return items.value }
    fun addBuckets(names: Collection<String>) = save(current() + names.map { "bucket:$it" })
    fun addDirs(paths: Collection<String>) = save(current() + paths.map { "dir:" + it.trimEnd('/') })
    fun addFiles(paths: Collection<String>) = save(current() + paths.map { "file:$it" })
    fun addPrivate(keys: Collection<String>) = save(current() + keys)
    fun remove(entries: Collection<String>) = save(current() - entries.toSet())
    fun clear() = save(emptySet())

    fun files(): List<String> = current().filter { it.startsWith("file:") }.map { it.removePrefix("file:") }

    /** Chaves que o motor do backup entende (álbum de mídia, pasta inteira, ou arquivos de uma pasta). */
    fun keys(): Set<String> {
        val out = LinkedHashSet<String>()
        for (e in current()) when {
            e.startsWith("bucket:") -> out += e.removePrefix("bucket:")
            e.startsWith("dir:") -> out += FileScan.keyFor(e.removePrefix("dir:"))
            e.startsWith("file:") -> File(e.removePrefix("file:")).parent?.let { out += FileScan.PICK + it.trimEnd('/') }
        }
        return out
    }

    /** Texto curto da fila ("2 álbuns, 1 pasta, 15 arquivos"). */
    fun describe(): String {
        val c = current()
        val b = c.count { it.startsWith("bucket:") }; val d = c.count { it.startsWith("dir:") }; val f = c.count { it.startsWith("file:") }
        val pv = c.count { it.startsWith(DeepScan.PREFIX) }
        return listOfNotNull(
            if (pv > 0) "$pv ${if (pv == 1) "grupo protegido" else "grupos protegidos"}" else null,
            if (b > 0) "$b ${if (b == 1) "álbum" else "álbuns"}" else null,
            if (d > 0) "$d ${if (d == 1) "pasta" else "pastas"}" else null,
            if (f > 0) "$f ${if (f == 1) "arquivo" else "arquivos"}" else null,
        ).joinToString(", ")
    }
}
