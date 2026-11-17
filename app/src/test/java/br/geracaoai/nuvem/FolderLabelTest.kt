package br.geracaoai.nuvem

import br.geracaoai.nuvem.ui.folderLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderLabelTest {
    @Test fun knownFoldersGetFriendlyNames() {
        assertEquals("Câmera", folderLabel("Camera"))
        assertEquals("Capturas", folderLabel("Screenshots"))
        assertEquals("Gravações", folderLabel("Screen recordings"))
        assertEquals("WhatsApp Vídeo", folderLabel("WhatsApp Video"))
        assertEquals("Telegram", folderLabel("Telegram"))
        assertEquals("Minha pasta", folderLabel("Minha pasta"))
    }
}
