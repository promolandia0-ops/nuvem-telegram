package br.geracaoai.nuvem.data

import br.geracaoai.nuvem.tg.TdException

/** Erros do Telegram ao processar foto (HEIC, HDR, arquivo estranho). Não podem parar o backup nem marcar como enviado. */
object ImageProcess {
    const val JPEG_MAX_SIDE = 2560

    fun isFailed(code: Int = 0, message: String?): Boolean {
        val m = (message ?: "").uppercase()
        if (m.contains("IMAGE_PROCESS_FAILED")) return true
        if (m.contains("PHOTO_INVALID") || m.contains("IMAGE_INVALID")) return true
        if (m.contains("FAILED_TO_PROCESS") && (m.contains("IMAGE") || m.contains("PHOTO"))) return true
        if (code == 400 && (m.contains("IMAGE") || m.contains("PHOTO"))) return true
        return false
    }

    fun isFailed(e: Throwable): Boolean {
        var cur: Throwable? = e
        var hops = 0
        while (cur != null && hops++ < 6) {
            val code = (cur as? TdException)?.code ?: 0
            if (isFailed(code, cur.message)) return true
            cur = cur.cause
        }
        return false
    }
}

data class UploadResult(
    val sent: List<Pair<LocalItem, String>> = emptyList(),
    val deferred: List<DeferredUpload> = emptyList(),
)

data class DeferredUpload(
    val item: LocalItem,
    val hash: String,
    val error: String,
    val folder: String,
    val chatId: Long,
    val topicId: Int,
)

data class FailedUpload(
    val path: String,
    val name: String,
    val hash: String,
    val size: Long,
    val folder: String,
    val mime: String,
    val error: String,
    val attempts: Int,
    val at: Long,
)
