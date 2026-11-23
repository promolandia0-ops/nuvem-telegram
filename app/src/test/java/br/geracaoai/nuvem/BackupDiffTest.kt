package br.geracaoai.nuvem

import br.geracaoai.nuvem.data.BackupDiff
import br.geracaoai.nuvem.data.CloudIdentity
import br.geracaoai.nuvem.data.ListedFile
import org.junit.Assert.*
import org.junit.Test

class BackupDiffTest {
    private fun f(path: String, size: Long, name: String = path.substringAfterLast('/'), readable: Boolean = true, bucket: String = "Segura", mtime: Long = 1, taken: Long = 1) =
        ListedFile(path, name, size, mtime, if (name.endsWith(".mp4")) "video/mp4" else "image/jpeg", bucket, "Movies/Segura/", readable)

    private fun cloud(path: String, size: Long, hash: String, name: String = path.substringAfterLast('/')) =
        CloudIdentity(path, name, size, hash)

    @Test fun mergeKeepsDiskFileWhenMediaStorePathIsStale() {
        val store = listOf(f("/sdcard/DCIM/Camera/a.mp4", 100, readable = false, bucket = "Camera"))
        val disk = listOf(f("/sdcard/Movies/Segura/a.mp4", 100, bucket = "Segura"))
        val merged = BackupDiff.mergeListings(store, disk)
        assertEquals(1, merged.size)
        assertEquals("/sdcard/Movies/Segura/a.mp4", merged[0].path)
        assertTrue(merged[0].readable)
    }

    @Test fun mergeAddsNewFileInAlreadyBackedFolder() {
        val old = f("/sdcard/Movies/Segura/old.mp4", 50)
        val moved = f("/sdcard/Movies/Segura/new.mp4", 999, taken = 10, mtime = 10)
        val merged = BackupDiff.mergeListings(listOf(old), listOf(old, moved))
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.name == "new.mp4" && it.size == 999L })
    }

    @Test fun movedFileWithOldDateIsStillPending() {
        val index = listOf(cloud("/sdcard/Movies/Segura/old.mp4", 50, "h-old"))
        val files = listOf(
            f("/sdcard/Movies/Segura/old.mp4", 50),
            f("/sdcard/Movies/Segura/moved.mp4", 800, taken = 1, mtime = 1), // data antiga
        )
        val hashes = mapOf(files[0].path to "h-old", files[1].path to "h-new")
        val pending = BackupDiff.pending(files, { hashes[it.path] }, index)
        assertEquals(listOf("moved.mp4"), pending.map { it.name })
    }

    @Test fun sameNameDifferentPathIsNotSkipped() {
        val index = listOf(cloud("/sdcard/DCIM/Camera/VID_001.mp4", 100, "h-cam"))
        val neu = f("/sdcard/Movies/Segura/VID_001.mp4", 100)
        assertFalse(BackupDiff.isUploaded(neu.path, neu.size, "h-new", index))
        assertFalse(BackupDiff.nameSizeSamePath(neu.path, neu.name, neu.size, index))
        assertTrue(BackupDiff.nameSizeSamePath("/sdcard/DCIM/Camera/VID_001.mp4", "VID_001.mp4", 100, index))
    }

    @Test fun hashMatchCountsAsUploadedEvenAfterMove() {
        val index = listOf(cloud("/sdcard/DCIM/Camera/a.mp4", 100, "h-a"))
        assertTrue(BackupDiff.isUploaded("/sdcard/Movies/Segura/a.mp4", 100, "h-a", index))
    }

    @Test fun biggestFirstOrdersBySize() {
        val list = listOf(f("/a.mp4", 10), f("/b.mp4", 500), f("/c.jpg", 20))
        assertEquals(listOf(500L, 20L, 10L), BackupDiff.biggestFirst(list).map { it.size })
        assertEquals(530L, BackupDiff.bytesOf(list))
    }

    @Test fun candidateRelPathsIncludeKnownAndCommon() {
        val p = BackupDiff.candidateRelPaths("Segura", listOf("Movies/Segura/"))
        assertTrue(p.contains("Movies/Segura/"))
        assertTrue(p.contains("DCIM/Segura/"))
        assertTrue(p.any { it == "Segura/" })
    }

    @Test fun pathBelongsToFolderUsesParentNameAndRelPath() {
        assertTrue(BackupDiff.pathBelongsToFolder("/storage/emulated/0/Movies/Segura/a.mp4", "Movies/Segura/", "Segura"))
        assertTrue(BackupDiff.pathBelongsToFolder("/storage/emulated/0/DCIM/Camera/a.jpg", "DCIM/Camera/", "Camera"))
        assertFalse(BackupDiff.pathBelongsToFolder("/storage/emulated/0/DCIM/Camera/a.jpg", "Movies/Segura/", "Segura"))
    }

    @Test fun isMediaFileRecognizesVideoAndPhoto() {
        assertTrue(BackupDiff.isMediaFile("clip.MP4"))
        assertTrue(BackupDiff.isMediaFile("pic.heic"))
        assertFalse(BackupDiff.isMediaFile("notes.txt"))
    }
}
