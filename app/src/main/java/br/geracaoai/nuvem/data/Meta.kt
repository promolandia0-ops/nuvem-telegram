package br.geracaoai.nuvem.data

import org.json.JSONObject

/** Metadados gravados na legenda de cada arquivo enviado. */
data class Meta(
    val hash: String, val size: Long, val taken: Long, val name: String,
    val mime: String, val folder: String, val path: String,
) {
    val isVideo get() = mime.startsWith("video/")

    fun toCaption(): String {
        val j = JSONObject().put("h", hash).put("s", size).put("t", taken).put("n", name)
            .put("m", mime).put("f", folder).put("p", path.take(300))
        return "$name\n$TAG${j}"
    }

    companion object {
        const val TAG = "#nuvem v1 "
        fun parse(caption: String?): Meta? {
            if (caption == null) return null
            val i = caption.indexOf(TAG); if (i < 0) return null
            return try {
                val j = JSONObject(caption.substring(i + TAG.length).trim())
                Meta(j.getString("h"), j.getLong("s"), j.optLong("t"), j.optString("n"),
                    j.optString("m"), j.optString("f"), j.optString("p"))
            } catch (_: Exception) { null }
        }
        /** Limite de tamanho do Telegram: 2 GB (4 GB com Premium). */
        fun maxSize(premium: Boolean): Long = if (premium) 4000L * 1024 * 1024 else 2000L * 1024 * 1024
    }
}
