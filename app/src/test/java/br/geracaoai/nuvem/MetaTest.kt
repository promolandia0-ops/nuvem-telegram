package br.geracaoai.nuvem

import br.geracaoai.nuvem.data.Hash
import br.geracaoai.nuvem.data.Meta
import br.geracaoai.nuvem.data.SendKind
import org.junit.Assert.*
import org.junit.Test

class MetaTest {
    private val m = Meta("abc123", 12345L, 1700000000000L, "IMG \"1\".jpg", "image/jpeg", "Camera", "DCIM/Camera/IMG 1.jpg")

    @Test fun roundTrip() { assertEquals(m, Meta.parse(m.toCaption())) }
    @Test fun captionStartsWithName() { assertTrue(m.toCaption().startsWith("IMG \"1\".jpg\n")) }
    @Test fun ignoresForeignCaptions() { assertNull(Meta.parse("foto qualquer")); assertNull(Meta.parse(null)); assertNull(Meta.parse("x\n#nuvem v1 {quebrado")) }
    @Test fun videoFlag() { assertTrue(m.copy(mime = "video/mp4").isVideo); assertFalse(m.isVideo) }
    @Test fun limits() { assertTrue(Meta.maxSize(true) > Meta.maxSize(false)); assertEquals(2000L * 1024 * 1024, Meta.maxSize(false)) }
    @Test fun longPathTruncated() { assertEquals(300, Meta.parse(m.copy(path = "a".repeat(1000)).toCaption())!!.path.length) }
    @Test fun sha256() { assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hash.sha256("abc".byteInputStream())) }
    @Test fun mimeFromName() {
        assertEquals("image/jpeg", Meta.mimeFromName("a.JPG"))
        assertEquals("video/mp4", Meta.mimeFromName("clip.mp4"))
        assertEquals("image/heic", Meta.mimeFromName("x.heic"))
        assertEquals("", Meta.mimeFromName("noext"))
    }
    @Test fun albumInCaption() {
        val cap = m.toCaption()
        assertTrue(cap.contains("📁 Álbum Camera"))
        assertEquals(m.copy(original = false), Meta.parse(cap))
        assertTrue(Meta.albumTitle("WhatsApp").contains("WhatsApp"))
    }
    @Test fun originalFlagRoundTrip() {
        val cap = m.copy(original = true).toCaption()
        assertFalse(cap.contains("📁 Álbum"))
        val p = Meta.parse(cap)!!
        assertTrue(p.original); assertEquals(m.hash, p.hash)
    }
    @Test fun sendKindPhotoVideoOrDocument() {
        assertEquals(SendKind.PHOTO, Meta.sendKind("image/jpeg", 1_000_000))
        assertEquals(SendKind.PHOTO, Meta.sendKind("image/png", 5_000_000))
        assertEquals(SendKind.VIDEO, Meta.sendKind("video/mp4", 80L * 1024 * 1024))
        assertEquals(SendKind.DOCUMENT, Meta.sendKind("image/gif", 2_000_000))
        assertEquals(SendKind.DOCUMENT, Meta.sendKind("application/pdf", 1000))
        assertEquals(SendKind.DOCUMENT, Meta.sendKind("image/jpeg", Meta.MEDIA_MAX + 1))
        assertEquals(SendKind.DOCUMENT, Meta.sendKind("video/mp4", 3000L * 1024 * 1024))
    }
}
