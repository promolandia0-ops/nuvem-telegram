package br.geracaoai.nuvem

import br.geracaoai.nuvem.data.DestKind
import br.geracaoai.nuvem.data.ImageProcess
import br.geracaoai.nuvem.tg.TdException
import org.junit.Assert.*
import org.junit.Test

class ImageProcessTest {
    @Test fun detectsImageProcessFailed() {
        assertTrue(ImageProcess.isFailed(400, "IMAGE_PROCESS_FAILED"))
        assertTrue(ImageProcess.isFailed(TdException(400, "PHOTO_INVALID")))
        assertTrue(ImageProcess.isFailed(TdException(400, "IMAGE_INVALID")))
        assertTrue(ImageProcess.isFailed(0, "failed_to_process the image"))
        assertTrue(ImageProcess.isFailed(400, "PHOTO something"))
        assertFalse(ImageProcess.isFailed(408, "Envio parado (sem progresso). Tentando de novo."))
        assertFalse(ImageProcess.isFailed(429, "FLOOD_WAIT"))
        assertFalse(ImageProcess.isFailed(0, "Job was cancelled"))
    }

    @Test fun walksCauseChain() {
        val inner = TdException(400, "IMAGE_PROCESS_FAILED")
        assertTrue(ImageProcess.isFailed(RuntimeException("wrapper", inner)))
    }
}

class DestKindTest {
    @Test fun labelsAndDefault() {
        assertEquals("group", DestKind.default())
        assertEquals("Grupo", DestKind.label(DestKind.GROUP))
        assertEquals("Grupo com tópicos", DestKind.label(DestKind.FORUM))
        assertEquals("Canal", DestKind.label(DestKind.CHANNEL))
        assertEquals("Mensagens salvas", DestKind.label(DestKind.SAVED))
        assertEquals("Grupo", DestKind.label(""))
        assertTrue(DestKind.hint(DestKind.GROUP).isNotBlank())
        assertTrue(DestKind.all.containsAll(listOf(DestKind.GROUP, DestKind.FORUM, DestKind.CHANNEL, DestKind.SAVED)))
    }
}
