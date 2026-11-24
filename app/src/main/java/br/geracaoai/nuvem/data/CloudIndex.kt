package br.geracaoai.nuvem.data

import android.content.Context
import br.geracaoai.nuvem.tg.CloudRec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cópia local da lista da nuvem, para a galeria abrir na hora.
 * A fonte da verdade continua sendo o Telegram: a lista é refeita a cada atualização.
 */
object CloudIndex {
    private val _items = MutableStateFlow<List<CloudRec>>(emptyList())
    val items: StateFlow<List<CloudRec>> = _items.asStateFlow()
    private var file: File? = null

    fun init(c: Context) {
        val f = File(c.filesDir, "cloud.json")
        file = f
        try {
            if (f.exists()) _items.value = parse(f.readText())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun replace(list: List<CloudRec>) {
        _items.value = sort(list)
        flush()
    }

    /** Acrescenta itens recém-enviados. Não grava a cada item: quem chama usa flush() no fim. */
    @Synchronized
    fun add(list: List<CloudRec>) {
        _items.value = sort(_items.value + list)
    }

    @Synchronized
    fun flush() {
        val f = file ?: return
        try {
            f.writeText(toJson(_items.value))
        } catch (_: Exception) {
        }
    }

    private fun sort(l: List<CloudRec>): List<CloudRec> = l.sortedByDescending { it.meta.taken }

    private fun toJson(l: List<CloudRec>): String {
        val arr = JSONArray()
        for (r in l) {
            arr.put(
                JSONObject()
                    .put("c", r.chatId)
                    .put("i", r.msgId)
                    .put("d", r.meta.duration)
                    .put("h", r.meta.hash)
                    .put("s", r.meta.size)
                    .put("t", r.meta.taken)
                    .put("n", r.meta.name)
                    .put("m", r.meta.mime)
                    .put("f", r.meta.folder)
                    .put("p", r.meta.path),
            )
        }
        return arr.toString()
    }

    private fun parse(text: String): List<CloudRec> {
        val arr = JSONArray(text)
        val out = ArrayList<CloudRec>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val meta = Meta(
                o.optString("h"), o.optLong("s"), o.optLong("t"), o.optString("n"),
                o.optString("m"), o.optString("f"), o.optString("p"), true, o.optInt("d"),
            )
            out += CloudRec(o.optLong("c"), o.optLong("i"), meta)
        }
        return out
    }
}
