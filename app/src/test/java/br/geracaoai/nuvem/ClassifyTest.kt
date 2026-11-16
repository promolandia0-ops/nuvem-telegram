package br.geracaoai.nuvem

import br.geracaoai.nuvem.data.*
import br.geracaoai.nuvem.tg.TdException
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class ClassifyTest {
    @Test fun slugAndCaption() {
        assertEquals("camera", Classify.slug("Câmera"))
        assertEquals("whatsapp", Classify.slug("WhatsApp"))
        val cal = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 16, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
        val cap = Classify.shortCaption(cal.timeInMillis, "Camera")
        assertEquals("#2026_11 #camera", cap)
        assertEquals("", Classify.shortCaption(1L, "Camera", original = true))
        assertFalse("#nuvem v1" in cap)
        assertTrue(cap.length < 40)
    }

    @Test fun screenshotHeuristic() {
        assertTrue(Classify.isScreenshot("Pictures/Screenshots/a.jpg", "Screenshot_1.png"))
        assertTrue(Classify.isScreenshot("DCIM/Screenshots", "img.png"))
        assertFalse(Classify.isScreenshot("DCIM/Camera/IMG_1.jpg", "IMG_1.jpg"))
    }

    @Test fun docHeuristic() {
        assertTrue(Classify.isDoc("Download/comprovante_pix.jpg", "image/jpeg"))
        assertTrue(Classify.isDoc("x.pdf", "application/pdf"))
        assertFalse(Classify.isDoc("DCIM/Camera/IMG.jpg", "image/jpeg"))
    }

    @Test fun cameraHeuristic() {
        assertTrue(Classify.isCamera("DCIM/Camera/IMG.jpg", "Camera"))
        assertFalse(Classify.isCamera("Pictures/Screenshots/a.jpg", "Screenshots"))
        assertFalse(Classify.isCamera("WhatsApp/Media", "WhatsApp"))
    }

    @Test fun memeHeuristic() {
        assertTrue(Classify.isMeme("WhatsApp/Media/Stickers", "sticker.webp", "image/webp", 20_000, 512, 512))
        assertFalse(Classify.isMeme("DCIM/Camera/IMG.jpg", "IMG.jpg", "image/jpeg", 3_000_000, 4000, 3000))
    }

    @Test fun summaryAndSize() {
        val s = backupSummary(120, 15, 0, (2.3 * (1L shl 30)).toLong())
        assertTrue(s.startsWith("✅ 120 fotos, 15 vídeos"))
        assertTrue("GB" in s)
    }

    @Test fun cancelDetection() {
        assertTrue(isCancelMessage("Job was cancelled"))
        assertTrue(isCancelMessage("StandaloneCoroutine was cancelled"))
        assertTrue(isCancel(java.util.concurrent.CancellationException("Job was cancelled")))
        assertFalse(isCancelMessage("upload failed"))
        assertFalse(isCancelMessage(null))
    }

    @Test fun createErrors() {
        assertTrue("Mensagens Salvas" in describeCreateError(TdException(400, "USER_RESTRICTED")))
        assertTrue("esperar 35 segundos" in describeCreateError(TdException(429, "FLOOD_WAIT_35")))
        assertTrue("muitos canais" in describeCreateError(Exception("CHANNELS_TOO_MUCH")))
        assertEquals("erro qualquer", describeCreateError(Exception("erro qualquer")))
    }

    @Test fun floodParse() {
        assertEquals(35, floodWaitSeconds("FLOOD_WAIT_35"))
        assertEquals(10, floodWaitSeconds("FLOOD_WAIT: 10"))
        assertNull(floodWaitSeconds("ok"))
    }

    @Test fun indexRoundTrip() {
        val item = CloudItem(10, 1, "a.jpg", "image/jpeg", 100, 1, "Camera", "DCIM/a.jpg", "abc", null)
        val recs = Meta.unpackIndex(Meta.packIndex(listOf(item)))
        assertEquals(1, recs.size)
        assertEquals(10L, recs[0].msgId)
        assertEquals("abc", recs[0].meta.hash)
        assertEquals("Camera", recs[0].meta.folder)
        assertFalse(recs[0].meta.original)
    }

    @Test fun legacyCaptionStillParses() {
        val m = Meta("abc123", 12345L, 1700000000000L, "IMG.jpg", "image/jpeg", "Camera", "DCIM/IMG.jpg")
        assertTrue(Meta.hasLegacyCaption(m.toCaption()))
        assertEquals(m, Meta.parse(m.toCaption()))
        assertFalse(Meta.hasLegacyCaption("#2026_11 #camera"))
    }
}
