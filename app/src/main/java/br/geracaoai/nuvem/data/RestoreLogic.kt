package br.geracaoai.nuvem.data

import org.json.JSONArray
import org.json.JSONObject

/** Caminho de destino e fila de restauração. Sem I/O. */
object RestoreLogic {
    val VALID_TOP = setOf(
        "DCIM", "Pictures", "Movies", "Download", "Downloads", "Documents",
        "Alarms", "Ringtones", "Notifications", "Podcasts", "Audiobooks", "Recordings", "Music",
    )

    fun restoreKey(chatId: Long, msgId: Long): String = "$chatId:$msgId"

    fun stripStoragePrefix(path: String): String {
        var p = path.trim().replace('\\', '/').trim('/')
        val markers = listOf("storage/emulated/0/", "storage/emulated/0", "sdcard/", "sdcard")
        val low = p.lowercase()
        for (m in markers) {
            val i = low.indexOf(m)
            if (i >= 0) {
                p = p.substring(i + m.length).trim('/')
                break
            }
        }
        if (p.startsWith("file:")) p = p.substringAfter("file:").trim('/')
        return p
    }

    fun mediaRelativePath(storedPath: String, folder: String, isVideo: Boolean): String {
        val raw = storedPath.trim()
        val fromFile = if (raw.contains('/')) stripStoragePrefix(raw).substringBeforeLast('/') else stripStoragePrefix(raw)
        val rel = fromFile.trim('/')
        val top = rel.substringBefore('/', rel)
        if (rel.isNotBlank() && top in VALID_TOP) return rel.trimEnd('/') + "/"
        val album = folder.trim().ifBlank { "Outros" }
        val base = if (isVideo) "Movies" else "Pictures"
        return "$base/Restaurados/$album/"
    }

    const val MAX_TRIES = 4

    data class Task(
        val chatId: Long,
        val msgId: Long,
        val name: String,
        val mime: String,
        val size: Long,
        val folder: String,
        val path: String,
        val taken: Long,
        val attempts: Int = 0,
        val state: String = "pending", // pending, running, done, failed
        val error: String = "",
    ) {
        val key get() = restoreKey(chatId, msgId)
        val isVideo get() = mime.startsWith("video/")
        val destRel get() = mediaRelativePath(path, folder, isVideo)
    }

    fun enqueue(existing: List<Task>, incoming: List<Task>): List<Task> {
        val seen = existing.map { it.key }.toHashSet()
        val out = existing.toMutableList()
        for (t in incoming) {
            if (t.key in seen) continue
            if (t.state == "done") continue
            seen += t.key
            out += t.copy(state = "pending", attempts = 0, error = "")
        }
        return out
    }

    fun nextPending(list: List<Task>): Task? = list.firstOrNull { it.state == "pending" || it.state == "running" }

    fun markRunning(list: List<Task>, key: String): List<Task> =
        list.map { if (it.key == key) it.copy(state = "running") else it }

    fun markDone(list: List<Task>, key: String): List<Task> =
        list.map { if (it.key == key) it.copy(state = "done", error = "") else it }

    fun markFailed(list: List<Task>, key: String, error: String): List<Task> =
        list.map { t ->
            if (t.key != key) t
            else {
                val n = t.attempts + 1
                if (n < MAX_TRIES) t.copy(state = "pending", attempts = n, error = error)
                else t.copy(state = "failed", attempts = n, error = error)
            }
        }

    /** Mantém só o que já restaurou; tira pendentes, em andamento e falhas. */
    fun cancelQueue(list: List<Task>): List<Task> = list.filter { it.state == "done" }

    fun pendingCount(list: List<Task>) = list.count { it.state == "pending" || it.state == "running" }
    fun doneCount(list: List<Task>) = list.count { it.state == "done" }
    fun failedCount(list: List<Task>) = list.count { it.state == "failed" }

    fun toJson(list: List<Task>): String {
        val a = JSONArray()
        list.forEach { t ->
            a.put(JSONObject()
                .put("chatId", t.chatId).put("msgId", t.msgId).put("name", t.name)
                .put("mime", t.mime).put("size", t.size).put("folder", t.folder)
                .put("path", t.path).put("taken", t.taken).put("attempts", t.attempts)
                .put("state", t.state).put("error", t.error))
        }
        return a.toString()
    }

    fun fromJson(raw: String?): List<Task> {
        if (raw.isNullOrBlank() || raw == "[]") return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Task(
                    o.optLong("chatId"), o.optLong("msgId"), o.optString("name"), o.optString("mime"),
                    o.optLong("size"), o.optString("folder"), o.optString("path"), o.optLong("taken"),
                    o.optInt("attempts"), o.optString("state", "pending"), o.optString("error"),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun fromCloud(ci: CloudItem) = Task(ci.chatId, ci.msgId, ci.name, ci.mime, ci.size, ci.folder, ci.path, ci.taken)
}
