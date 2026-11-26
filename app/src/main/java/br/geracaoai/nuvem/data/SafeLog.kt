package br.geracaoai.nuvem.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LogEntry(val at: Long, val action: String, val detail: String, val count: Int, val bytes: Long)

/** Registro local do que o app fez (liberar espaço, duplicadas, restauração). */
object ActionLog {
    private const val KEY = "action_log"
    val latest = MutableStateFlow<List<LogEntry>>(emptyList())

    fun load() { latest.value = read() }

    fun add(action: String, detail: String, count: Int = 0, bytes: Long = 0) {
        val e = LogEntry(System.currentTimeMillis(), action, detail, count, bytes)
        val all = (listOf(e) + read()).take(80)
        write(all); latest.value = all
    }

    fun recent(n: Int = 30): List<LogEntry> = latest.value.ifEmpty { read() }.take(n)

    private fun read(): List<LogEntry> {
        val raw = try { Prefs.raw.getString(KEY, "[]") } catch (_: Exception) { "[]" }
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                LogEntry(o.optLong("at"), o.optString("action"), o.optString("detail"), o.optInt("count"), o.optLong("bytes"))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun write(list: List<LogEntry>) {
        val a = JSONArray()
        list.forEach { e ->
            a.put(JSONObject().put("at", e.at).put("action", e.action).put("detail", e.detail).put("count", e.count).put("bytes", e.bytes))
        }
        try { Prefs.raw.edit().putString(KEY, a.toString()).apply() } catch (_: Exception) {}
    }
}

/** Itens que falharam no backup (não foram marcados como enviados). */
object FailStore {
    private const val KEY = "failed_uploads"
    val items = MutableStateFlow<List<FailedUpload>>(emptyList())

    fun load() { items.value = read() }

    fun replace(list: List<FailedUpload>) { write(list); items.value = list }

    fun addAll(list: List<FailedUpload>) {
        if (list.isEmpty()) return
        val byHash = (read() + list).associateBy { it.hash.ifBlank { it.path } }
        replace(byHash.values.sortedByDescending { it.at })
    }

    fun remove(hash: String) { replace(read().filter { it.hash != hash }) }

    fun clear() { replace(emptyList()) }

    /** Tira da lista o que não existe mais no celular (apagado, movido ou na lixeira). Devolve quantos saíram. */
    fun pruneMissing(): Int {
        val cur = read()
        val keep = cur.filter { it.path.isBlank() || java.io.File(it.path).isFile }
        if (keep.size != cur.size) replace(keep)
        return cur.size - keep.size
    }

    private fun read(): List<FailedUpload> {
        val raw = try { Prefs.raw.getString(KEY, "[]") } catch (_: Exception) { "[]" }
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                FailedUpload(
                    o.optString("path"), o.optString("name"), o.optString("hash"), o.optLong("size"),
                    o.optString("folder"), o.optString("mime"), o.optString("error"), o.optInt("attempts"), o.optLong("at"),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun write(list: List<FailedUpload>) {
        val a = JSONArray()
        list.forEach { e ->
            a.put(JSONObject().put("path", e.path).put("name", e.name).put("hash", e.hash).put("size", e.size)
                .put("folder", e.folder).put("mime", e.mime).put("error", e.error).put("attempts", e.attempts).put("at", e.at))
        }
        try { Prefs.raw.edit().putString(KEY, a.toString()).apply() } catch (_: Exception) {}
    }

    fun fromDeferred(d: DeferredUpload, attempts: Int = 1) = FailedUpload(
        d.item.path, d.item.name, d.hash, d.item.size, d.folder, d.item.mime, d.error, attempts, System.currentTimeMillis(),
    )
}

object SafeTrash {
    /** Lixeira do sistema (recuperável ~30 dias). Nunca apaga permanente nem cria .nomedia. */
    fun intent(c: Context, uris: List<Uri>) = try {
        if (uris.isEmpty()) null
        else MediaStore.createTrashRequest(c.contentResolver, uris, true).intentSender
    } catch (_: Exception) { null }
}
