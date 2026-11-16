package br.geracaoai.nuvem.data

import org.json.JSONObject

/** Metadados gravados na legenda de cada arquivo enviado. */
data class Meta(
    val hash: String, val size: Long, val taken: Long, val name: String,
    val mime: String, val folder: String, val path: String,
    val original: Boolean = false,
) {
    val isVideo get() = mime.startsWith("video/")

    fun toCaption(): String {
        val j = JSONObject().put("h", hash).put("s", size).put("t", taken).put("n", name)
            .put("m", mime).put("f", folder).put("p", path.take(300))
        if (original) j.put("k", "orig")
        val album = if (folder.isNotBlank() && !original && folder != WATCHED_FOLDER) "\n${albumTitle(folder)}" else ""
        return "$name$album\n$TAG${j}"
    }

    companion object {
        const val TAG = "#nuvem v1 "
        /** Limite de tamanho do Telegram: 2 GB (4 GB com Premium). */
        fun maxSize(premium: Boolean): Long = if (premium) 4000L * 1024 * 1024 else 2000L * 1024 * 1024
        /** Acima de 2 GB o Telegram só aceita documento (e só com Premium). */
        const val MEDIA_MAX = 2000L * 1024 * 1024

        fun parse(caption: String?): Meta? {
            if (caption == null) return null
            val i = caption.indexOf(TAG); if (i < 0) return null
            return try {
                val j = JSONObject(caption.substring(i + TAG.length).trim())
                Meta(j.getString("h"), j.getLong("s"), j.optLong("t"), j.optString("n"),
                    j.optString("m"), j.optString("f"), j.optString("p"), j.optString("k") == "orig")
            } catch (_: Exception) { null }
        }

        fun albumTitle(folder: String) = "📁 Álbum ${folder.ifBlank { "Outros" }}"

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

        /** Como tentar enviar: foto/vídeo (aparece no Telegram como mídia) ou documento (arquivo). */
        fun sendKind(mime: String, size: Long): SendKind {
            if (size > MEDIA_MAX) return SendKind.DOCUMENT
            val m = mime.lowercase()
            return when {
                m.startsWith("image/") && m != "image/gif" && !m.contains("svg") -> SendKind.PHOTO
                m.startsWith("video/") -> SendKind.VIDEO
                else -> SendKind.DOCUMENT
            }
        }
    }
}

enum class SendKind { PHOTO, VIDEO, DOCUMENT }
