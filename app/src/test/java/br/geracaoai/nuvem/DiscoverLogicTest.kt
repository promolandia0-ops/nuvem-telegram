package br.geracaoai.nuvem

import br.geracaoai.nuvem.data.CloudIdentity
import br.geracaoai.nuvem.data.CloudItem
import br.geracaoai.nuvem.data.DiscoverLogic
import br.geracaoai.nuvem.data.ListedFile
import br.geracaoai.nuvem.data.ORIGINALS_FOLDER
import br.geracaoai.nuvem.data.WATCHED_FOLDER
import org.junit.Assert.*
import org.junit.Test

class DiscoverLogicTest {
    private fun f(path: String, size: Long, mtime: Long = 1, readable: Boolean = true) = ListedFile(
        path, path.substringAfterLast('/'), size, mtime,
        if (path.endsWith(".mp4")) "video/mp4" else "image/jpeg",
        "Segura", "Movies/Segura/", readable, 0, mtime,
    )
    private fun cloud(folder: String, path: String, size: Long, name: String = path.substringAfterLast('/'), hash: String = "h") =
        CloudItem(1, 1, name, "video/mp4", size, 1, folder, path, hash, null)

    @Test fun pendingIncludesMovedFileWithOldMtime() {
        val index = listOf(CloudIdentity("/sdcard/Movies/Segura/old.mp4", "old.mp4", 50, "h-old"))
        val files = listOf(
            f("/sdcard/Movies/Segura/old.mp4", 50, mtime = 9_999),
            f("/sdcard/Movies/Segura/moved.mp4", 800, mtime = 1),
        )
        val pending = DiscoverLogic.pendingFast(files, index)
        assertEquals(listOf("moved.mp4"), pending.map { it.name })
    }

    @Test fun pendingFindsFileInsideNomediaFolder() {
        val index = emptyList<CloudIdentity>()
        val hidden = f("/sdcard/Movies/Segura/privado/a.mp4", 120, mtime = 10)
        // mesmo com pasta .nomedia, o arquivo no disco entra
        val pending = DiscoverLogic.pendingFast(listOf(hidden), index)
        assertEquals(1, pending.size)
        assertEquals("a.mp4", pending[0].name)
    }

    @Test fun shouldEnterNomediaAndHiddenButSkipThumbnails() {
        assertTrue(DiscoverLogic.shouldEnterDir("Segura", isWalkRoot = true))
        assertTrue(DiscoverLogic.shouldEnterDir(".privada"))
        assertTrue(DiscoverLogic.walkNomediaFolders())
        assertFalse(DiscoverLogic.shouldEnterDir(".thumbnails"))
        assertFalse(DiscoverLogic.shouldEnterChild("Android", "data"))
        assertTrue(DiscoverLogic.shouldEnterChild("Segura", "sub"))
    }

    @Test fun normalizeAlbumUsesLastSegmentAndOutros() {
        assertEquals("Camera", DiscoverLogic.normalizeAlbum("DCIM/Camera", ""))
        assertEquals("Segura", DiscoverLogic.normalizeAlbum("Movies/Segura/", ""))
        assertEquals("Camera", DiscoverLogic.normalizeAlbum("Camera", "/storage/emulated/0/DCIM/Camera/a.jpg"))
        assertEquals("Outros", DiscoverLogic.normalizeAlbum("", ""))
        assertEquals("Camera", DiscoverLogic.normalizeAlbum("", "/storage/emulated/0/DCIM/Camera/a.jpg"))
        assertEquals("Segura", DiscoverLogic.albumFromPath("/storage/emulated/0/Movies/Segura/clip.mp4"))
    }

    @Test fun groupAlbumsListsEveryTelegramFolderEvenIfMissingLocally() {
        val items = listOf(
            cloud("DCIM/Camera", "/storage/emulated/0/DCIM/Camera/a.jpg", 10),
            cloud("Camera", "/storage/emulated/0/DCIM/Camera/b.jpg", 20),
            cloud("", "/storage/emulated/0/Pictures/x.jpg", 5), // Pictures
            cloud("", "", 8, name = "solto.jpg"), // Outros
            cloud("Segura", "/storage/emulated/0/Movies/Segura/gone.mp4", 100), // já apagado do celular
            cloud(ORIGINALS_FOLDER, "/x", 1),
            cloud(WATCHED_FOLDER, "/y", 1),
        )
        val albums = DiscoverLogic.groupAlbums(items)
        val names = albums.map { it.name }.toSet()
        assertTrue(names.contains("Camera"))
        assertTrue(names.contains("Segura"))
        assertTrue(names.contains("Pictures"))
        assertTrue(names.contains("Outros"))
        assertFalse(names.contains(ORIGINALS_FOLDER))
        assertFalse(names.contains(WATCHED_FOLDER))
        val cam = albums.first { it.name == "Camera" }
        assertEquals(2, cam.count)
        assertEquals(30L, cam.bytes)
        val segura = albums.first { it.name == "Segura" }
        assertEquals(1, segura.count)
        assertEquals(100L, segura.bytes)
        assertEquals(4, albums.size)
    }

    @Test fun cancelQueueKeepsDoneAndDropsTheRest() {
        val done = br.geracaoai.nuvem.data.RestoreLogic.Task(1, 1, "a.mp4", "video/mp4", 1, "C", "", 0, 0, "done")
        val run = done.copy(msgId = 2, name = "b.mp4", state = "running")
        val pend = done.copy(msgId = 3, name = "c.mp4", state = "pending")
        val out = br.geracaoai.nuvem.data.RestoreLogic.cancelQueue(listOf(done, run, pend))
        assertEquals(listOf("done"), out.map { it.state }.distinct())
        assertEquals(1, out.size)
        assertEquals("a.mp4", out[0].name)
    }
}
