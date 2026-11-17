package br.geracaoai.nuvem

import br.geracaoai.nuvem.data.CloudSync
import br.geracaoai.nuvem.data.FolderPlan
import br.geracaoai.nuvem.data.Hash
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FolderPlanTest {
    @Test fun cameraGoesFirstThenAlphabetical() {
        val ordered = FolderPlan.order(listOf("WhatsApp Video", "Telegram", "Camera", "Screenshots", "Screen recordings", "Câmera"))
        assertEquals("Camera", ordered[0])
        assertEquals("Câmera", ordered[1])
        assertEquals(listOf("Screen recordings", "Screenshots", "Telegram", "WhatsApp Video"), ordered.drop(2))
    }

    @Test fun albumNameIsThePhoneFolder() {
        assertEquals("Telegram", FolderPlan.albumName("Telegram"))
        assertEquals("WhatsApp Video", FolderPlan.albumName("WhatsApp Video"))
        assertEquals("Screenshots", FolderPlan.albumName("Screenshots"))
        assertEquals("Screen recordings", FolderPlan.albumName("Screen recordings"))
        assertEquals("Outros", FolderPlan.albumName(""))
        assertFalse(FolderPlan.albumName("Telegram") == "Vídeos longos")
    }

    private data class Item(val name: String, val doc: Boolean = false, val folder: String = "Camera")

    @Test fun emitsGroupsOfTenInOrderAndKeepsRemainder() {
        val buf = (1..25).map { Item("v$it.mp4") }.toMutableList()
        val first = FolderPlan.pullReadyChunks(buf, flush = false, isDoc = { it.doc }, max = 10)
        assertEquals(2, first.size)
        assertEquals((1..10).map { "v$it.mp4" }, first[0].map { it.name })
        assertEquals((11..20).map { "v$it.mp4" }, first[1].map { it.name })
        assertEquals((21..25).map { "v$it.mp4" }, buf.map { it.name })
        val last = FolderPlan.pullReadyChunks(buf, flush = true, isDoc = { it.doc }, max = 10)
        assertEquals(1, last.size)
        assertEquals((21..25).map { "v$it.mp4" }, last[0].map { it.name })
        assertTrue(buf.isEmpty())
    }

    @Test fun doesNotMixMediaAndDocuments() {
        val buf = mutableListOf(Item("a.jpg"), Item("b.jpg"), Item("c.pdf", doc = true), Item("d.jpg"))
        val mid = FolderPlan.pullReadyChunks(buf, flush = false, isDoc = { it.doc }, max = 10)
        assertEquals(2, mid.size)
        assertEquals(listOf("a.jpg", "b.jpg"), mid[0].map { it.name })
        assertEquals(listOf("c.pdf"), mid[1].map { it.name })
        assertEquals(listOf("d.jpg"), buf.map { it.name })
    }

    @Test fun foldersNeverInterleaveWhenConsumedInOrder() {
        val folders = FolderPlan.order(listOf("Telegram", "Camera"))
        val log = ArrayList<String>()
        for (f in folders) {
            val buf = (1..12).map { Item("x$it", folder = f) }.toMutableList()
            val chunks = FolderPlan.pullReadyChunks(buf, flush = true, isDoc = { it.doc }, max = 10)
            for (c in chunks) log += c.map { f }
            log += "MARKER:$f"
        }
        assertEquals("Camera", folders[0])
        assertEquals("Telegram", folders[1])
        assertEquals(List(12) { "Camera" } + "MARKER:Camera" + List(12) { "Telegram" } + "MARKER:Telegram", log)
    }
}

class CloudSyncTest {
    @Test fun missingAfterHistoryCleared() {
        val stored = listOf(1L, 2L, 3L, 4L)
        assertEquals(stored, CloudSync.missingIds(stored, emptySet()))
        assertEquals(listOf(2L, 4L), CloudSync.missingIds(stored, setOf(1L, 3L)))
        assertTrue(CloudSync.missingIds(stored, stored.toSet()).isEmpty())
        assertTrue(CloudSync.missingIds(emptyList(), setOf(1L)).isEmpty())
    }

    @Test fun foundIdsIgnoresNullAndZero() {
        assertEquals(setOf(10L, 20L), CloudSync.foundIds(listOf(10L, null, 0L, 20L)))
    }

    @Test fun goneDetection() {
        assertTrue(CloudSync.isMessageGone(404, "Not Found"))
        assertTrue(CloudSync.isMessageGone(400, "MESSAGE_NOT_FOUND"))
        assertFalse(CloudSync.isMessageGone(408, "Tempo esgotado"))
        assertTrue(CloudSync.isChatGone(400, "CHAT_NOT_FOUND"))
    }
}

class HashQuickTest {
    private fun write(file: File, bytes: ByteArray) { file.parentFile.mkdirs(); file.writeBytes(bytes) }

    @Test fun sameContentSameHash() {
        val dir = File("build/tmp-hash-test").apply { mkdirs() }
        val a = File(dir, "a.bin"); val b = File(dir, "b.bin")
        val data = ByteArray(2_500_000) { (it % 251).toByte() }
        write(a, data); write(b, data)
        assertEquals(Hash.quick(a), Hash.quick(b))
        assertTrue(Hash.quick(a).startsWith("q:"))
    }

    @Test fun differentContentOrSizeChangesHash() {
        val dir = File("build/tmp-hash-test").apply { mkdirs() }
        val a = File(dir, "c.bin"); val b = File(dir, "d.bin"); val c = File(dir, "e.bin")
        write(a, ByteArray(2_000_000) { 1 })
        write(b, ByteArray(2_000_000) { 1 }.also { it[it.lastIndex] = 2 })
        write(c, ByteArray(2_000_001) { 1 })
        assertNotEquals(Hash.quick(a), Hash.quick(b))
        assertNotEquals(Hash.quick(a), Hash.quick(c))
    }

    @Test fun smallFileIsStable() {
        val dir = File("build/tmp-hash-test").apply { mkdirs() }
        val a = File(dir, "s.bin")
        write(a, "hello-nuvem".toByteArray())
        assertEquals(Hash.quick(a), Hash.quick(a))
        assertTrue(Hash.quick(a).startsWith("q:"))
    }
}
