package br.geracaoai.nuvem

import br.geracaoai.nuvem.data.RestoreLogic
import org.junit.Assert.*
import org.junit.Test

class RestoreLogicTest {
    @Test fun relativePathFromAbsoluteGoesToOriginalAlbum() {
        assertEquals("DCIM/Camera/", RestoreLogic.mediaRelativePath("/storage/emulated/0/DCIM/Camera/a.jpg", "Camera", false))
        assertEquals("Movies/Segura/", RestoreLogic.mediaRelativePath("/storage/emulated/0/Movies/Segura/v.mp4", "Segura", true))
        assertEquals("DCIM/Camera/", RestoreLogic.mediaRelativePath("storage/emulated/0/DCIM/Camera/a.jpg", "Camera", false))
    }

    @Test fun alreadyRelativePathIsKept() {
        assertEquals("DCIM/Camera/", RestoreLogic.mediaRelativePath("DCIM/Camera/a.jpg", "Camera", false))
        assertEquals("Pictures/Nuvem/Viagens/", RestoreLogic.mediaRelativePath("Pictures/Nuvem/Viagens/x.jpg", "Viagens", false))
    }

    @Test fun missingOrInvalidPathFallsBackToRestaurados() {
        assertEquals("Movies/Restaurados/Câmera/", RestoreLogic.mediaRelativePath("", "Câmera", true))
        assertEquals("Pictures/Restaurados/Outros/", RestoreLogic.mediaRelativePath("", "", false))
        // caminho absoluto inválido (sem pasta de mídia conhecida)
        assertEquals("Movies/Restaurados/Segura/", RestoreLogic.mediaRelativePath("/data/user/0/foo/bar.mp4", "Segura", true))
    }

    @Test fun queueEnqueuesWithoutDuplicatesAndRetriesThenFails() {
        val a = RestoreLogic.Task(1, 10, "a.mp4", "video/mp4", 1, "Camera", "DCIM/Camera/a.mp4", 0)
        val b = RestoreLogic.Task(1, 11, "b.jpg", "image/jpeg", 1, "Camera", "DCIM/Camera/b.jpg", 0)
        var q = RestoreLogic.enqueue(emptyList(), listOf(a, b, a))
        assertEquals(2, q.size)
        q = RestoreLogic.enqueue(q, listOf(a))
        assertEquals(2, q.size)
        assertEquals(a.key, RestoreLogic.nextPending(q)!!.key)
        q = RestoreLogic.markRunning(q, a.key)
        q = RestoreLogic.markFailed(q, a.key, "rede")
        assertEquals("pending", q.first { it.key == a.key }.state)
        assertEquals(1, q.first { it.key == a.key }.attempts)
        repeat(RestoreLogic.MAX_TRIES - 1) { q = RestoreLogic.markFailed(q, a.key, "rede") }
        assertEquals("failed", q.first { it.key == a.key }.state)
        q = RestoreLogic.markDone(q, b.key)
        assertEquals(0, RestoreLogic.pendingCount(q))
        assertEquals(1, RestoreLogic.doneCount(q))
        assertEquals(1, RestoreLogic.failedCount(q))
        val again = RestoreLogic.fromJson(RestoreLogic.toJson(q))
        assertEquals(q.map { it.key to it.state }, again.map { it.key to it.state })
    }
}
