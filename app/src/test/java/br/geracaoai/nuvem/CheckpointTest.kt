package br.geracaoai.nuvem

import br.geracaoai.nuvem.data.Checkpoint
import br.geracaoai.nuvem.data.CheckpointState
import br.geracaoai.nuvem.data.StallPolicy
import org.junit.Assert.*
import org.junit.Test

class CheckpointTest {
    @Test fun encodeRoundTripKeepsProgress() {
        val s = CheckpointState(folder = "Camera", done = 12, total = 90, startedAt = 1000L, incomplete = true, lastItem = "/dcim/a.jpg|100|9")
        val back = Checkpoint.decode(Checkpoint.encode(s))!!
        assertEquals("Camera", back.folder)
        assertEquals(12, back.done)
        assertEquals(90, back.total)
        assertEquals(1000L, back.startedAt)
        assertTrue(back.incomplete)
        assertEquals("/dcim/a.jpg|100|9", back.lastItem)
        assertEquals("Retomando Camera • 12 de 90", back.label)
    }

    @Test fun decodeBlankIsNullAndItemKeyIsStable() {
        assertNull(Checkpoint.decode(null))
        assertNull(Checkpoint.decode(""))
        assertNull(Checkpoint.decode("não é json"))
        assertEquals("/a/b.mp4|42|7", Checkpoint.itemKey("/a/b.mp4", 42, 7))
    }

    @Test fun stallAfterWindowAndRetryBudget() {
        val t0 = 1_000_000L
        assertFalse(StallPolicy.stalled(t0, t0 + 30_000))
        assertFalse(StallPolicy.stalled(0, t0 + 200_000))
        assertTrue(StallPolicy.stalled(t0, t0 + StallPolicy.STALL_MS))
        assertTrue(StallPolicy.shouldRetry(0))
        assertTrue(StallPolicy.shouldRetry(2))
        assertFalse(StallPolicy.shouldRetry(3))
        assertEquals(2000L, StallPolicy.backoffMs(0))
        assertTrue(StallPolicy.backoffMs(20) <= 15_000L)
    }

    @Test fun fgsTimeoutIsDetectedJustBeforeSixHours() {
        val start = 0L
        assertFalse(StallPolicy.nearTimeout(start, 4 * 60 * 60_000L))
        assertTrue(StallPolicy.nearTimeout(start, StallPolicy.FGS_SAFE_MS))
        assertTrue(StallPolicy.nearTimeout(start, 6 * 60 * 60_000L))
    }
}
