package br.geracaoai.nuvem

import br.geracaoai.nuvem.data.Dedup
import br.geracaoai.nuvem.data.DupItem
import org.junit.Assert.*
import org.junit.Test

class DedupTest {
    private fun item(
        id: Long, hash: String = "h", size: Long = 100, date: Int = id.toInt(),
        name: String = "a.jpg", mime: String = "image/jpeg", original: Boolean = false,
        unique: String = "", taken: Long = date * 1000L, folder: String = "Camera",
    ) = DupItem(id, 1, name, mime, size, taken, date, folder, hash, unique, original)

    @Test fun keepsOldestPhotoAndItsOriginal() {
        val photo1 = item(10, date = 100)
        val photo2 = item(20, date = 200)
        val orig1 = item(11, date = 101, mime = "image/jpeg", original = true, name = "a.jpg")
        val orig2 = item(21, date = 201, mime = "image/jpeg", original = true, name = "a.jpg")
        val plan = Dedup.plan(listOf(photo2, orig2, photo1, orig1))
        assertEquals(setOf(10L, 11L), plan.keep.map { it.msgId }.toSet())
        assertEquals(setOf(20L, 21L), plan.delete.map { it.msgId }.toSet())
        assertEquals(1, plan.deletePhotos)
        assertEquals(1, plan.deleteDocs)
        assertTrue("duplicados" in plan.preview)
    }

    @Test fun videosAreDedupedToo() {
        val a = item(1, hash = "vid", size = 5_000, name = "v.mp4", mime = "video/mp4", date = 50)
        val b = item(2, hash = "vid", size = 5_000, name = "v.mp4", mime = "video/mp4", date = 80)
        val plan = Dedup.plan(listOf(b, a))
        assertEquals(listOf(1L), plan.keep.map { it.msgId })
        assertEquals(listOf(2L), plan.delete.map { it.msgId })
        assertEquals(1, plan.deleteVideos)
        assertEquals(0, plan.deletePhotos)
        assertTrue("vídeo" in plan.preview)
    }

    @Test fun documentsDedupedByNameAndSizeWhenNoHash() {
        val a = item(3, hash = "", size = 200, name = "nota.pdf", mime = "application/pdf", date = 10, unique = "")
        val b = item(4, hash = "", size = 200, name = "nota.pdf", mime = "application/pdf", date = 11, unique = "")
        assertEquals("n:nota.pdf:200", Dedup.groupKey(b))
        val plan = Dedup.plan(listOf(a, b))
        assertEquals(listOf(3L), plan.keep.map { it.msgId })
        assertEquals(listOf(4L), plan.delete.map { it.msgId })
    }

    @Test fun uniqueIdPlusSizeGroupsWhenHashMissing() {
        val a = item(5, hash = "", size = 999, name = "x.jpg", unique = "uid-1", date = 1)
        val b = item(6, hash = "", size = 999, name = "y.jpg", unique = "uid-1", date = 2)
        val plan = Dedup.plan(listOf(a, b))
        assertEquals(listOf(5L), plan.keep.map { it.msgId })
        assertEquals(listOf(6L), plan.delete.map { it.msgId })
    }

    @Test fun uniqueIdDoesNotMergePhotoAndOriginalOfDifferentIds() {
        val photo = item(7, hash = "", size = 80, name = "a.jpg", unique = "comp", date = 1)
        val orig = item(8, hash = "", size = 800, name = "a.jpg", unique = "orig", date = 2, original = true)
        val plan = Dedup.plan(listOf(photo, orig))
        assertTrue(plan.delete.isEmpty())
        assertEquals(2, plan.keep.size)
    }

    @Test fun singlesAreKept() {
        val plan = Dedup.plan(listOf(item(1, hash = "a"), item(2, hash = "b", mime = "video/mp4", name = "b.mp4")))
        assertTrue(plan.delete.isEmpty())
        assertEquals(2, plan.keep.size)
    }

    @Test fun claimHashPreventsSecondEnqueue() {
        val seen = HashSet<String>()
        assertTrue(Dedup.claimHash(seen, "abc", 10))
        assertFalse(Dedup.claimHash(seen, "abc", 10))
        assertTrue(Dedup.claimHash(seen, "abc", 11))
        assertTrue(Dedup.claimHash(seen, "zzz", 10))
        assertTrue(Dedup.claimHash(seen, "", 10)) // no hash: don't block
    }

    @Test fun olderPrefersMessageDate() {
        val newer = item(100, date = 5, taken = 1)
        val older = item(200, date = 1, taken = 9_999)
        assertEquals(200L, Dedup.plan(listOf(newer, older)).keep.single().msgId)
    }
}
