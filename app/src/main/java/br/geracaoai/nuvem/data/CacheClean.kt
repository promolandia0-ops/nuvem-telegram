package br.geracaoai.nuvem.data

import android.content.Context
import java.io.File

/**
 * Limpar cache: miniaturas e temporários do app, vídeos/fotos que o Telegram baixou só pra assistir
 * (ele baixa de novo se precisar) e o diário de diagnóstico. Nada da nuvem é apagado,
 * nem a conta, nem a lista do que já foi enviado.
 */
object CacheClean {
    /** Pastas de arquivos baixados pelo Telegram (cache de visualização). */
    private val TD_CACHE = listOf("videos", "documents", "photos", "thumbnails", "animations", "music", "voice", "video_notes", "stickers")

    fun size(c: Context): Long = targets(c).sumOf { du(it) } + File(c.filesDir, "diag.log").length()

    /**
     * Apaga e devolve quantos bytes saíram. Os temporários do MEGA (arquivo que está subindo) nunca saem.
     * Com [busy] (backup rodando), o cache do app fica e só sai o cache do Telegram e o diário.
     */
    fun run(c: Context, busy: Boolean): Long {
        var freed = 0L
        val cache = c.cacheDir.absolutePath
        for (dir in targets(c)) {
            if (dir.name == "mega_dl" || dir.name == "mega") continue
            if (busy && dir.absolutePath.startsWith(cache)) continue
            freed += clean(dir)
        }
        freed += File(c.filesDir, "diag.log").length()
        DiagLog.clear()
        try { MediaScan.invalidate() } catch (_: Exception) {}
        DiagLog.log("APP", "cache e diagnóstico limpos (${freed / (1024 * 1024)} MB)")
        return freed
    }

    private fun targets(c: Context): List<File> {
        val out = ArrayList<File>()
        c.cacheDir.listFiles()?.let { out += it }
        val td = File(c.filesDir, "tdfiles")
        TD_CACHE.forEach { out += File(td, it) }
        return out.filter { it.exists() }
    }

    private fun du(f: File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { du(it) } ?: 0L)

    private fun clean(f: File): Long {
        if (f.isFile) { val n = f.length(); return if (f.delete()) n else 0L }
        var n = 0L
        f.listFiles()?.forEach { n += clean(it) }
        // a pasta raiz do cache do Telegram fica (só o conteúdo sai)
        return n
    }
}
