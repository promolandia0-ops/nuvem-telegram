package br.geracaoai.nuvem.data

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/** Heurísticas simples (pasta, nome, tamanho, proporção, duração) pra separar em tópicos. */
object Classify {
    const val PRINTS = "Prints"
    const val MEMES = "Memes"
    const val DOCS = "Documentos"
    const val LONG_VIDEOS = "Vídeos longos"
    const val CAMERA = "Câmera"
    const val LONG_VIDEO_MS = 180_000L

    fun slug(name: String): String {
        val n = Normalizer.normalize(name, Normalizer.Form.NFD).replace("\\p{M}+".toRegex(), "")
        return n.lowercase().replace("[^a-z0-9]+".toRegex(), "_").trim('_').take(24).ifBlank { "album" }
    }

    fun monthTag(taken: Long): String {
        val t = if (taken > 0) taken else System.currentTimeMillis()
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return "#%04d_%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    /** Legenda curta visível: no máximo duas hashtags, sem JSON nem nome de arquivo. */
    fun shortCaption(taken: Long, folder: String, original: Boolean = false): String {
        if (original) return ""
        return "${monthTag(taken)} #${slug(folder)}"
    }

    fun topicOf(item: LocalItem, durationMs: Long = 0L, width: Int = 0, height: Int = 0, smart: Boolean = true): String {
        if (!smart) return item.bucket.ifBlank { "Outros" }
        val path = listOf(item.path, item.relPath, item.bucket, item.name).joinToString(" ").lowercase()
        val mime = item.mime.lowercase()
        if (isDoc(path, mime)) return DOCS
        if (isScreenshot(path, item.name)) return PRINTS
        if (isMeme(path, item.name, mime, item.size, width, height)) return MEMES
        if (mime.startsWith("video/") && durationMs >= LONG_VIDEO_MS) return LONG_VIDEOS
        if (isCamera(path, item.bucket)) return CAMERA
        return item.bucket.ifBlank { "Outros" }
    }

    fun isScreenshot(path: String, name: String): Boolean {
        val n = name.lowercase(); val p = path.lowercase()
        val keys = listOf("screenshot", "screen_shot", "screen-shot", "screenshots", "printscreen", "print_screen", "screencap", "captura de tela", "captura_de_tela")
        if (keys.any { it in p || it in n }) return true
        if (n.startsWith("screenshot") || n.startsWith("screen-") || n.startsWith("screen_")) return true
        return false
    }

    fun isDoc(path: String, mime: String): Boolean {
        if (mime == "application/pdf" || mime.contains("officedocument") || mime.contains("msword") || mime == "application/vnd.ms-excel") return true
        val p = path.lowercase()
        return listOf("comprovante", "boleto", "recibo", "receipt", "invoice", "extrato", "nf-e", "nfe_", "pix.comprov").any { it in p }
    }

    fun isMeme(path: String, name: String, mime: String, size: Long, width: Int, height: Int): Boolean {
        val p = (path + " " + name).lowercase()
        if ("sticker" in p || "stickers" in p || "meme" in p) return true
        if (name.lowercase().endsWith(".webp") && size in 1..500_000L && "dcim" !in p && "camera" !in p) return true
        if (mime.startsWith("image/") && size in 1..250_000L && width in 200..800 && height in 200..800) {
            val ratio = width.toFloat() / height.coerceAtLeast(1)
            if (ratio in 0.7f..1.4f && "dcim" !in p && "camera" !in p && "screenshot" !in p) return true
        }
        return false
    }

    fun isCamera(path: String, bucket: String): Boolean {
        val p = (path + " " + bucket).lowercase()
        if ("screenshot" in p || "whatsapp" in p) return false
        return "dcim" in p || bucket.equals("Camera", true) || bucket.equals("Câmera", true) ||
            bucket.equals("Camera2", true) || Regex("(?i)(^|\\b)c[aâ]mera(\\b|$)").containsMatchIn(bucket)
    }
}

fun formatSize(b: Long): String {
    val df = DecimalFormat("#,##0.#", DecimalFormatSymbols(Locale("pt", "BR")))
    return when {
        b >= 1L shl 30 -> df.format(b / 1073741824.0) + " GB"
        b >= 1L shl 20 -> df.format(b / 1048576.0) + " MB"
        else -> df.format(b / 1024.0) + " KB"
    }
}

fun backupSummary(photos: Int, videos: Int, docs: Int, bytes: Long): String {
    val parts = ArrayList<String>()
    parts += "$photos fotos"
    parts += "$videos vídeos"
    if (docs > 0) parts += "$docs arquivos"
    return "✅ ${parts.joinToString(", ")}, ${formatSize(bytes)}"
}

fun isCancelMessage(m: String?): Boolean {
    if (m.isNullOrBlank()) return false
    val s = m.lowercase()
    return "job was cancelled" in s || "jobcancellationexception" in s ||
        "standalone coroutine was cancelled" in s || "standalonecoroutine was cancelled" in s ||
        (s == "cancelled") || s.endsWith("was cancelled")
}

fun isCancel(e: Throwable): Boolean {
    var c: Throwable? = e
    while (c != null) {
        if (c is java.util.concurrent.CancellationException) return true
        if (isCancelMessage(c.message)) return true
        c = c.cause
    }
    return false
}


fun floodWaitSeconds(message: String?): Int? {
    if (message.isNullOrBlank()) return null
    Regex("""FLOOD_WAIT[_: ]*(\d+)""", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    Regex("""wait of (\d+) seconds""", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    return null
}

/** Mensagem clara quando o Telegram recusa criar grupo/canal (comum em conta nova). */
fun describeCreateError(e: Throwable): String {
    val m = e.message ?: ""
    val sec = floodWaitSeconds(m)
    if (sec != null || (e is br.geracaoai.nuvem.tg.TdException && e.code == 429) || "FLOOD" in m.uppercase()) {
        val espera = if (sec != null) " $sec segundos" else ""
        return "O Telegram pediu pra esperar$espera. Contas novas têm esse limite. Tente de novo em seguida, ou use Mensagens Salvas."
    }
    val u = m.uppercase()
    val blocked = listOf("USER_RESTRICTED", "FROZEN", "UNAVAILABLE", "CHAT_CREATE", "CREATE_CHANNEL", "CREATE_GROUP",
        "NOT ALLOWED", "IS NOT ALLOWED", "NOT YET", "TOO YOUNG", "PHONE_NUMBER_BANNED", "PEER_FLOOD")
    if (blocked.any { it in u }) {
        return "O Telegram não deixou criar grupo ou canal nesta conta (bem comum em contas novas). Espere alguns dias, ou use Mensagens Salvas por enquanto."
    }
    if ("CHANNELS_TOO_MUCH" in u || "CHATS_TOO_MUCH" in u) return "Você já tem muitos canais ou grupos no Telegram."
    if ("Tempo esgotado" in m) return "O Telegram demorou demais pra criar o grupo. Confira a internet e tente de novo."
    return m.ifBlank { "Não foi possível criar o grupo ou canal." }
}
