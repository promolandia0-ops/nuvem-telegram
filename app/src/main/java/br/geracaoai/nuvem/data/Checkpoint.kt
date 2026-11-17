package br.geracaoai.nuvem.data

import org.json.JSONObject

/** Progresso persistido pra retomar o backup exatamente de onde parou, sem reenviar. */
data class CheckpointState(
    val folder: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val startedAt: Long = 0L,
    val incomplete: Boolean = false,
    val lastItem: String = "",
) {
    val label: String get() = when {
        !incomplete -> ""
        folder.isNotBlank() && total > 0 -> "Retomando $folder • $done de $total"
        folder.isNotBlank() -> "Retomando $folder"
        else -> "Retomando o backup"
    }
}

object Checkpoint {
    private const val KEY = "backup_checkpoint"

    fun itemKey(path: String, size: Long, mtime: Long): String = "$path|$size|$mtime"

    fun encode(s: CheckpointState): String = JSONObject().apply {
        put("folder", s.folder); put("done", s.done); put("total", s.total)
        put("startedAt", s.startedAt); put("incomplete", s.incomplete); put("lastItem", s.lastItem)
    }.toString()

    fun decode(raw: String?): CheckpointState? {
        if (raw.isNullOrBlank()) return null
        return try {
            val j = JSONObject(raw)
            CheckpointState(
                folder = j.optString("folder"),
                done = j.optInt("done"),
                total = j.optInt("total"),
                startedAt = j.optLong("startedAt"),
                incomplete = j.optBoolean("incomplete"),
                lastItem = j.optString("lastItem"),
            )
        } catch (_: Exception) { null }
    }

    fun load(): CheckpointState? = try { decode(Prefs.raw.getString(KEY, "")) } catch (_: Exception) { null }
    fun save(s: CheckpointState) { try { Prefs.raw.edit().putString(KEY, encode(s)).commit() } catch (_: Exception) {} }
    fun mark(folder: String, done: Int, total: Int, lastItem: String = "", startedAt: Long = load()?.startedAt?.takeIf { it > 0 } ?: System.currentTimeMillis()) {
        save(CheckpointState(folder, done, total, startedAt, incomplete = true, lastItem = lastItem))
    }
    fun clear() { try { Prefs.raw.edit().remove(KEY).commit() } catch (_: Exception) {} }
    fun shouldResume(): Boolean = load()?.incomplete == true
}

object StallPolicy {
    const val STALL_MS = 120_000L
    const val MAX_TRIES = 3
    /** Android 15 limita dataSync a 6 h. Paramos um pouco antes pra gravar e continuar. */
    const val FGS_SAFE_MS = 5 * 60 * 60_000L + 20 * 60_000L

    fun stalled(lastProgressAt: Long, now: Long, windowMs: Long = STALL_MS): Boolean {
        if (lastProgressAt <= 0L) return false
        return now - lastProgressAt >= windowMs
    }

    fun shouldRetry(attempt: Int, max: Int = MAX_TRIES): Boolean = attempt in 0 until max

    fun nearTimeout(startedAtElapsed: Long, nowElapsed: Long, limitMs: Long = FGS_SAFE_MS): Boolean =
        nowElapsed - startedAtElapsed >= limitMs

    fun backoffMs(attempt: Int): Long = (2_000L * (attempt + 1)).coerceAtMost(15_000L)
}
