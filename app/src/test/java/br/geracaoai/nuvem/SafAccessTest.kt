package br.geracaoai.nuvem

import br.geracaoai.nuvem.data.SafAccess
import org.junit.Assert.*
import org.junit.Test

class SafAccessTest {
    @Test fun firstCandidateIsPublicMediaTelegram() {
        assertEquals("primary:Android/media/org.telegram.messenger/Telegram", SafAccess.candidateIds().first())
        assertTrue(SafAccess.treeHint().contains("Android/media/org.telegram.messenger/Telegram"))
    }

    @Test fun acceptsTelegramFolders() {
        assertTrue(SafAccess.isTelegramDocId("primary:Android/media/org.telegram.messenger/Telegram"))
        assertTrue(SafAccess.isTelegramDocId("primary:Android/media/org.telegram.messenger"))
        assertTrue(SafAccess.isTelegramDocId("primary:Telegram"))
        assertTrue(SafAccess.isTelegramDocId("primary:Telegram/Telegram Video"))
        assertTrue(SafAccess.isTelegramDocId("primary:Movies/Telegram"))
        assertTrue(SafAccess.isTelegramDocId("primary:Download/Telegram"))
        assertTrue(SafAccess.isTelegramDocId("primary:Android/data/org.telegram.messenger"))
        assertTrue(SafAccess.isTelegramDocId("primary:Android/data/org.telegram.messenger/cache"))
        assertTrue(SafAccess.isTelegramDocId("primary:A\u200Bndroid/data/org.telegram.messenger"))
        assertTrue(SafAccess.isTelegramDocId("primary:Android/data"))
    }

    @Test fun rejectsUnrelatedFolders() {
        assertFalse(SafAccess.isTelegramDocId("primary:Download"))
        assertFalse(SafAccess.isTelegramDocId("primary:DCIM/Camera"))
        assertFalse(SafAccess.isTelegramDocId("primary:Android/media/com.whatsapp"))
        assertFalse(SafAccess.isTelegramDocId("primary:Android/data/com.whatsapp"))
    }
}
