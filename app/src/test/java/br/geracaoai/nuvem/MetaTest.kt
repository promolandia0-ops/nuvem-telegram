package br.geracaoai.nuvem

import br.geracaoai.nuvem.data.Hash
import br.geracaoai.nuvem.data.Meta
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
}
