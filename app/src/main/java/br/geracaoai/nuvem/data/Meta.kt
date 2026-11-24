package br.geracaoai.nuvem.data

import org.json.JSONObject

/** Dados gravados na legenda de cada arquivo enviado. O formato é o mesmo da versão anterior. */
data class Meta(
    val hash: String,
    val size: Long,
    val taken: Long,
    val name: String,
    val mime: String,
    val folder: String,
    val path: String,
    val original: Boolean = true,
    val duration: Int = 0,
) {
    val isVideo: Boolean get() = mime.startsWith("video/")

    fun toCaption(): String {
        val j = JSONObject()
            .put("h", hash)
            .put("s", size)
            .put("t", taken)
            .put("n", name)
            .put("m", mime)
            .put("f", folder)
            .put("p", path.take(300))
        if (original) j.put("k", "orig")
        if (duration > 0) j.put("d", duration)
        return "$name\n$TAG$j"
    }

    companion object {
        const val TAG = "#nuvem v1 "

        /** Tamanho máximo por arquivo: 2 GB no Telegram comum, 4 GB com Premium. */
        fun maxSize(premium: Boolean): Long = if (premium) 4000L * 1024 * 1024 else 2000L * 1024 * 1024

        fun parse(caption: String?): Meta? {
            if (caption.isNullOrEmpty()) return null
            val i = caption.indexOf(TAG)
            if (i < 0) return null
            return try {
                val j = JSONObject(caption.substring(i + TAG.length).trim())
                Meta(
                    j.getString("h"),
                    j.getLong("s"),
                    j.optLong("t"),
                    j.optString("n"),
                    j.optString("m"),
                    j.optString("f"),
                    j.optString("p"),
                    j.optString("k") == "orig",
                    j.optInt("d"),
                )
            } catch (_: Exception) {
                null
            }
        }

        fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "jpe" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            else -> ""
        }
    }
}
