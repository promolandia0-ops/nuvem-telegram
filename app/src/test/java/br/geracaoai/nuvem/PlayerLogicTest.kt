package br.geracaoai.nuvem

import br.geracaoai.nuvem.data.*
import org.junit.Assert.*
import org.junit.Test

class PlayerLogicTest {
    @Test fun puloSeisSegundos() {
        assertEquals(4_000L, PuloToque.alvo(10_000, 60_000, true))
        assertEquals(16_000L, PuloToque.alvo(10_000, 60_000, false))
        assertEquals(0L, PuloToque.alvo(2_000, 60_000, true))
        assertEquals(60_000L, PuloToque.alvo(58_000, 60_000, false))
        assertEquals("−6 s", PuloToque.hud(true))
        assertEquals("+6 s", PuloToque.hud(false))
    }

    @Test fun loopAB() {
        assertEquals(1_000L, Repeticao.posicaoAposTick(5_000, 1_000, 4_000))
        assertNull(Repeticao.posicaoAposTick(2_000, 1_000, 4_000))
        assertNull(Repeticao.posicaoAposTick(3_000, 1_000, 1_200))
        assertEquals(10L to null, Repeticao.marcarPonto(null, null, 10))
        assertEquals(10L to 20L, Repeticao.marcarPonto(10, null, 20))
        assertEquals(30L to null, Repeticao.marcarPonto(10, 20, 30))
        assertEquals(ModoRepeticao.UM, ModoRepeticao.DESLIGADO.proximo())
    }

    @Test fun temporizador() {
        assertFalse(TemporizadorSono.esgotou(1_000, 0, 100_000))
        assertFalse(TemporizadorSono.esgotou(1_000, 1, 1_000 + 59_000))
        assertTrue(TemporizadorSono.esgotou(1_000, 1, 1_000 + 60_000))
        assertEquals("Desligado", TemporizadorSono.rotulo(0))
        assertEquals("15 min", TemporizadorSono.rotulo(15))
    }

    @Test fun velocidadeCiclo() {
        assertEquals(1.25f, Velocidade.proxima(1f), 0.001f)
        assertEquals(0.25f, Velocidade.proxima(4f), 0.001f)
        assertEquals("1×", Velocidade.rotulo(1f))
        assertEquals("0.25×", Velocidade.rotulo(0.25f))
        assertEquals("2×", Velocidade.rotulo(2f))
    }

    @Test fun fileSizeAndBuffer() {
        assertEquals(100L, PlaybackFile.resolvedSize(100, 200, 50))
        assertEquals(200L, PlaybackFile.resolvedSize(0, 200, 50))
        assertEquals(50L, PlaybackFile.resolvedSize(0, 0, 50))
        assertEquals(0L, PlaybackFile.resolvedSize(0, 0, 0))
        assertFalse(PlaybackFile.isFileIdValid(0))
        assertTrue(PlaybackFile.isFileIdValid(7))
        assertFalse(PlaybackFile.readyToStart(0, 10_000_000))
        assertFalse(PlaybackFile.readyToStart(100_000, 10_000_000))
        assertTrue(PlaybackFile.readyToStart(512 * 1024, 10_000_000))
        assertTrue(PlaybackFile.readyToStart(80, 80))
        assertTrue(PlaybackFile.rangeAvailable(0, 1000, 0, 500))
        assertFalse(PlaybackFile.rangeAvailable(0, 100, 0, 500))
        assertTrue(PlaybackFile.rangeAvailable(1_000, 2_000, 1_200, 100))
        assertFalse(PlaybackFile.rangeAvailable(1_000, 2_000, 0, 100))
        assertEquals(0L, PlaybackFile.tailOffset(500_000))
        assertEquals(9_000_000L - (1 shl 20), PlaybackFile.tailOffset(9_000_000))
        assertEquals(23, PlaybackFile.progressPercent(23, 100))
        assertEquals(0, PlaybackFile.progressPercent(10, 0))
        assertTrue("23%" in PlaybackFile.rotuloProgresso(23, 100) || "23" in PlaybackFile.rotuloProgresso(23, 100))
    }

    @Test fun navSkipsPhotos() {
        val v = booleanArrayOf(false, true, false, true, false)
        assertEquals(3, PlaybackNav.proximoVideo(v, 1))
        assertNull(PlaybackNav.proximoVideo(v, 3))
        assertEquals(1, PlaybackNav.anteriorVideo(v, 3))
        assertNull(PlaybackNav.anteriorVideo(v, 1))
    }

    @Test fun tempoFmt() {
        assertEquals("00:00", TempoFmt.fmt(0))
        assertEquals("00:05", TempoFmt.fmt(5_000))
        assertEquals("01:01", TempoFmt.fmt(61_000))
        assertEquals("1:00:00", TempoFmt.fmt(3_600_000))
    }

    @Test fun orientacao() {
        assertEquals(true, Orientacao.ehPaisagem(1920, 1080))
        assertEquals(false, Orientacao.ehPaisagem(1080, 1920))
        assertNull(Orientacao.ehPaisagem(100, 100))
        assertEquals(true, Orientacao.ehPaisagem(1080, 1920, 90))
        assertNull(Orientacao.ehPaisagem(0, 10))
        assertEquals(ModoAspecto.PREENCHER, ModoAspecto.AJUSTAR.proximo())
    }

    @Test fun faixaAudio() {
        assertEquals("Faixa 1 · pt · estéreo · mp4a-latm", InfoVideo.rotuloFaixa(0, "pt", 2, "audio/mp4a-latm"))
        assertEquals("Faixa 2 · mono", InfoVideo.rotuloFaixa(1, "und", 1, null))
        assertEquals("1920×1080", InfoVideo.resolucao(1920, 1080))
        assertEquals("—", InfoVideo.resolucao(0, 0))
    }

    @Test fun retomarPosicao() {
        assertTrue(ResumePrefs.deveRetomar(10_000, 60_000))
        assertFalse(ResumePrefs.deveRetomar(1_000, 60_000))
        assertFalse(ResumePrefs.deveRetomar(58_000, 60_000))
    }
}
