package br.geracaoai.nuvem.data

import android.content.Context

/** Continuar o vídeo de onde parou. */
object ResumePrefs {
    private const val MIN_MS = 5_000L
    private const val FIM_MS = 5_000L
    val versao = androidx.compose.runtime.mutableIntStateOf(0)
    fun marcarAlterado() { versao.intValue++ }

    private fun p(ctx: Context) = ctx.getSharedPreferences("nuvem_posicao", Context.MODE_PRIVATE)

    fun chave(ci: CloudItem): String = "${ci.chatId}:${ci.msgId}"

    fun salvar(ctx: Context, chave: String, posMs: Long, durMs: Long) {
        if (chave.isBlank()) return
        if (durMs > 0 && posMs >= durMs - FIM_MS) { p(ctx).edit().remove("p:$chave").apply(); return }
        if (posMs < MIN_MS) { p(ctx).edit().remove("p:$chave").apply(); return }
        p(ctx).edit().putLong("p:$chave", posMs).apply()
    }

    fun obter(ctx: Context, chave: String, durMs: Long = 0L): Long? {
        if (!p(ctx).contains("p:$chave")) return null
        val pos = p(ctx).getLong("p:$chave", 0L)
        if (pos < MIN_MS) return null
        if (durMs > 0 && pos >= durMs - FIM_MS) return null
        return pos
    }

    fun deveRetomar(posMs: Long, durMs: Long): Boolean {
        if (posMs < MIN_MS) return false
        if (durMs > 0 && posMs >= durMs - FIM_MS) return false
        return true
    }
}
