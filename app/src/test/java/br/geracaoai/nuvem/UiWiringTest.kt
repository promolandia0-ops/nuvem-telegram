package br.geracaoai.nuvem

import android.app.Application

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import br.geracaoai.nuvem.ui.FeatureTags
import br.geracaoai.nuvem.ui.FeatureWiringHost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UiWiringTest {
    @get:Rule val compose = createComposeRule()

    private fun host() = compose.setContent { MaterialTheme { FeatureWiringHost() } }

    @Test fun fotosShortcutOpensFotos() {
        host()
        compose.onNodeWithTag(FeatureTags.SHORTCUT_FOTOS).performClick()
        compose.onNodeWithTag(FeatureTags.PANE_FOTOS).assertIsDisplayed()
    }

    @Test fun videosShortcutOpensVideos() {
        host()
        compose.onNodeWithTag(FeatureTags.SHORTCUT_VIDEOS).performClick()
        compose.onNodeWithTag(FeatureTags.PANE_VIDEOS).assertIsDisplayed()
    }

    @Test fun restaurarShortcutOpensRestore() {
        host()
        compose.onNodeWithTag(FeatureTags.SHORTCUT_RESTAURAR).performClick()
        compose.onNodeWithTag(FeatureTags.PANE_RESTAURAR).assertIsDisplayed()
        compose.onNodeWithText("Restaurar álbum").assertIsDisplayed()
    }

    @Test fun liberarEspacoOpensPaneWithCountAndConfirm() {
        host()
        compose.onNodeWithTag(FeatureTags.SHORTCUT_LIBERAR).performClick()
        compose.onNodeWithTag(FeatureTags.PANE_LIBERAR).assertIsDisplayed()
        compose.onNodeWithText("3 itens já salvos no Telegram", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Liberar 12 MB").performClick()
        compose.onNodeWithText("Mover 3 itens para a lixeira?").assertIsDisplayed()
        compose.onNodeWithText("Mover para a lixeira").assertIsDisplayed()
    }

    @Test fun duplicadasShortcutOpensPane() {
        host()
        compose.onNodeWithTag(FeatureTags.SHORTCUT_DUPLICADAS).performClick()
        compose.onNodeWithTag(FeatureTags.PANE_DUPLICADAS).assertIsDisplayed()
    }

    @Test fun ajustesShortcutOpensSettings() {
        host()
        compose.onNodeWithTag(FeatureTags.SHORTCUT_AJUSTES).performClick()
        compose.onNodeWithTag(FeatureTags.PANE_AJUSTES).assertIsDisplayed()
        for (tag in listOf(
            FeatureTags.SETTINGS_DESTINO, FeatureTags.SETTINGS_EMAIL, FeatureTags.SETTINGS_SESSAO,
            FeatureTags.SETTINGS_LEGENDAS, FeatureTags.SETTINGS_LIBERAR, FeatureTags.SETTINGS_REINDEXAR,
            FeatureTags.SETTINGS_TROCAR_DESTINO, FeatureTags.SETTINGS_SAIR,
        )) {
            compose.onNodeWithTag(tag).performScrollTo().assertExists()
        }
    }

    @Test fun settingsLiberarOpensFreeUp() {
        host()
        compose.onNodeWithTag(FeatureTags.SHORTCUT_AJUSTES).performClick()
        compose.onNodeWithTag(FeatureTags.SETTINGS_LIBERAR).performScrollTo().performClick()
        compose.onNodeWithTag(FeatureTags.PANE_LIBERAR).assertIsDisplayed()
    }

    @Test fun settingsDestinoOpensConfirm() {
        host()
        compose.onNodeWithTag(FeatureTags.SHORTCUT_AJUSTES).performClick()
        compose.onNodeWithTag(FeatureTags.SETTINGS_DESTINO).performClick()
        compose.onNodeWithText("Trocar destino?").assertIsDisplayed()
    }

    @Test fun settingsTrocarDestinoOpensConfirm() {
        host()
        compose.onNodeWithTag(FeatureTags.SHORTCUT_AJUSTES).performClick()
        compose.onNodeWithTag(FeatureTags.SETTINGS_TROCAR_DESTINO).performScrollTo().performClick()
        compose.onNodeWithText("Trocar destino?").assertIsDisplayed()
    }
}
