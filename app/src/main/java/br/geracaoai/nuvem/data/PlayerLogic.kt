package br.geracaoai.nuvem.data

/** Toque duplo: ±6 s. */
object PuloToque {
    const val MS = 10_000L
    fun alvo(pos: Long, dur: Long, esquerda: Boolean): Long {
        val d = if (esquerda) -MS else MS
        val fim = if (dur > 0L) dur else Long.MAX_VALUE
        return (pos + d).coerceIn(0L, fim)
    }
    fun hud(esquerda: Boolean): String = if (esquerda) "−10 s" else "+10 s"
}

enum class ModoRepeticao(val rotulo: String) {
    DESLIGADO("Repetir: desligado"),
    UM("Repetir vídeo"),
    AB("Repetir A-B");
    fun proximo(): ModoRepeticao = entries[(ordinal + 1) % entries.size]
}

object Repeticao {
    fun posicaoAposTick(pos: Long, aMs: Long?, bMs: Long?): Long? {
        if (aMs == null || bMs == null) return null
        val ini = minOf(aMs, bMs)
        val fim = maxOf(aMs, bMs)
        if (fim - ini < 400L) return null
        return if (pos >= fim) ini else null
    }
    fun marcarPonto(atualA: Long?, atualB: Long?, pos: Long): Pair<Long?, Long?> = when {
        atualA == null -> pos to null
        atualB == null -> atualA to pos
        else -> pos to null
    }
}

object TemporizadorSono {
    val opcoesMin = listOf(0, 15, 30, 45, 60)
    fun rotulo(min: Int): String = if (min <= 0) "Desligado" else "$min min"
    fun esgotou(inicioMs: Long, duracaoMin: Int, agoraMs: Long): Boolean {
        if (duracaoMin <= 0) return false
        return agoraMs - inicioMs >= duracaoMin * 60_000L
    }
}

object InfoVideo {
    fun resolucao(largura: Int, altura: Int): String =
        if (largura > 0 && altura > 0) "${largura}×${altura}" else "—"
    fun rotuloFaixa(indice: Int, idioma: String?, canais: Int, mime: String?): String {
        val partes = ArrayList<String>()
        partes += "Faixa ${indice + 1}"
        if (!idioma.isNullOrBlank() && idioma != "und") partes += idioma
        if (canais > 0) partes += if (canais == 1) "mono" else if (canais == 2) "estéreo" else "$canais canais"
        if (!mime.isNullOrBlank()) partes += mime.substringAfterLast('/')
        return partes.joinToString(" · ")
    }
}

object Velocidade {
    val opcoes = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
    fun proxima(atual: Float): Float {
        val i = opcoes.indexOfFirst { kotlin.math.abs(it - atual) < 0.001f }
        return opcoes[(if (i < 0) 0 else i + 1) % opcoes.size]
    }
    fun rotulo(v: Float): String {
        val s = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString().trimEnd('0').trimEnd('.')
        return "${s}×"
    }
}

enum class ModoAspecto(val rotulo: String) {
    AJUSTAR("Ajustar"),
    PREENCHER("Preencher"),
    ESTICAR("Esticar");
    fun proximo(): ModoAspecto = entries[(ordinal + 1) % entries.size]
}

enum class ModoOrientacao(val rotulo: String) {
    AUTO("Automática"),
    PAISAGEM("Deitado"),
    RETRATO("Em pé");
    fun proximo(): ModoOrientacao = entries[(ordinal + 1) % entries.size]
}

object Orientacao {
    /** true = deitado, false = em pé, null = ainda não dá pra saber (ou quadrado). */
    fun ehPaisagem(largura: Int, altura: Int, rotacaoGraus: Int = 0): Boolean? {
        if (largura <= 0 || altura <= 0) return null
        val r = ((rotacaoGraus % 360) + 360) % 360
        val troca = r == 90 || r == 270
        val w = if (troca) altura else largura
        val h = if (troca) largura else altura
        return when {
            w > h -> true
            w < h -> false
            else -> null
        }
    }
}

object PlaybackFile {
    const val MIN_START_BYTES = 512 * 1024L
    const val TAIL_BYTES = 1L shl 20

    fun resolvedSize(size: Long, expectedSize: Long, fallback: Long): Long = when {
        size > 0L -> size
        expectedSize > 0L -> expectedSize
        fallback > 0L -> fallback
        else -> 0L
    }

    fun isFileIdValid(id: Int): Boolean = id > 0

    fun readyToStart(downloadedPrefix: Long, size: Long, minBytes: Long = MIN_START_BYTES): Boolean {
        if (downloadedPrefix <= 0L) return false
        if (size > 0L && downloadedPrefix >= size) return true
        return downloadedPrefix >= minBytes
    }

    fun rangeAvailable(downloadOffset: Long, prefixSize: Long, wantOffset: Long, wantLen: Long): Boolean {
        if (wantLen <= 0L) return true
        if (prefixSize <= 0L) return false
        val end = wantOffset + wantLen
        val availEnd = downloadOffset + prefixSize
        return wantOffset >= downloadOffset && end <= availEnd
    }

    fun tailOffset(size: Long, tail: Long = TAIL_BYTES): Long =
        if (size <= tail) 0L else size - tail

    fun progressPercent(downloaded: Long, size: Long): Int {
        if (downloaded <= 0L) return 0
        if (size <= 0L) return 0
        return ((downloaded * 100L) / size).toInt().coerceIn(0, 100)
    }

    fun rotuloProgresso(downloaded: Long, size: Long): String {
        val pct = progressPercent(downloaded, size)
        return if (size > 0L) "Carregando $pct%  ·  ${formatSize(downloaded)} de ${formatSize(size)}"
        else "Carregando…  ·  ${formatSize(downloaded)}"
    }
}

object PlaybackNav {
    fun proximoVideo(videos: BooleanArray, index: Int): Int? {
        if (videos.isEmpty()) return null
        var i = index + 1
        while (i < videos.size) { if (videos[i]) return i; i++ }
        return null
    }
    fun anteriorVideo(videos: BooleanArray, index: Int): Int? {
        var i = index - 1
        while (i >= 0) { if (videos[i]) return i; i-- }
        return null
    }
}

object TempoFmt {
    fun fmt(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
    }
}
