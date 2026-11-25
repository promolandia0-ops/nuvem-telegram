package br.geracaoai.nuvem.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import br.geracaoai.nuvem.R
import br.geracaoai.nuvem.data.Prefs
import java.text.DecimalFormat

/** Paleta original: céu noturno índigo com aurora (ciano → violeta). Não é o visual do Gerenciador. */
object Nv {
    val Night = Color(0xFF070B16)
    val Deep = Color(0xFF0C1224)
    val Card = Color(0xFF151C33)
    val CardHi = Color(0xFF1C2542)
    val Ink = Color(0xFFF4F6FF)
    val Muted = Color(0xFF9AA3C2)
    val Line = Color(0x33FFFFFF)
    val Sky = Color(0xFF7C9CFF)
    val SkyDeep = Color(0xFF4F6FFF)
    val Cyan = Color(0xFF3EE0C9)
    val Violet = Color(0xFFB794F6)
    val Rose = Color(0xFFFF8BA7)
    val Gold = Color(0xFFF5C16C)
    val Danger = Color(0xFFFF6B7A)
    val Paper = Color(0xFFF4F2FF)
    val PaperCard = Color(0xFFFFFFFF)
    val InkDark = Color(0xFF14182A)
    val MutedLight = Color(0xFF5C6480)

    val Aurora = Brush.linearGradient(listOf(Cyan, Sky, Violet))
    val AuroraV = Brush.verticalGradient(listOf(Cyan, Sky, Violet))
    val Hero = Brush.linearGradient(listOf(Color(0xFF1A2450), Color(0xFF0E1630), Color(0xFF1A1040)))
}

val PlusJakarta = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

private val NvTypography = Typography(
    displaySmall = TextStyle(fontFamily = PlusJakarta, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = PlusJakarta, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = PlusJakarta, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = PlusJakarta, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = PlusJakarta, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = PlusJakarta, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = PlusJakarta, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = PlusJakarta, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = PlusJakarta, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = PlusJakarta, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = PlusJakarta, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = PlusJakarta, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = PlusJakarta, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp),
)

private val DarkScheme = darkColorScheme(
    primary = Nv.Sky, onPrimary = Color.White, primaryContainer = Color(0xFF1E2A5A), onPrimaryContainer = Nv.Ink,
    secondary = Nv.Cyan, onSecondary = Color(0xFF04221C), secondaryContainer = Color(0xFF163832), onSecondaryContainer = Nv.Cyan,
    tertiary = Nv.Violet, onTertiary = Color(0xFF1A1028),
    background = Nv.Night, onBackground = Nv.Ink,
    surface = Nv.Deep, onSurface = Nv.Ink, surfaceVariant = Nv.Card, onSurfaceVariant = Nv.Muted,
    surfaceContainer = Nv.Card, surfaceContainerHigh = Nv.CardHi, surfaceContainerLow = Nv.Deep, surfaceContainerLowest = Nv.Night,
    outline = Nv.Line, outlineVariant = Color(0x1FFFFFFF),
    error = Nv.Danger, onError = Color.White,
)

private val LightScheme = lightColorScheme(
    primary = Nv.SkyDeep, onPrimary = Color.White, primaryContainer = Color(0xFFDCE4FF), onPrimaryContainer = Color(0xFF15204A),
    secondary = Color(0xFF0F9E8D), onSecondary = Color.White,
    tertiary = Color(0xFF7A5CC8), background = Nv.Paper, onBackground = Nv.InkDark,
    surface = Nv.PaperCard, onSurface = Nv.InkDark, surfaceVariant = Color(0xFFE8E6F6), onSurfaceVariant = Nv.MutedLight,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerLow = Nv.Paper,
    outline = Color(0x3314182A), outlineVariant = Color(0x1A14182A), error = Nv.Danger,
)

@Composable
fun isNvDark(): Boolean {
    val t by Prefs.theme.collectAsState()
    return when (t) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
}

@Composable
fun NuvemTheme(content: @Composable () -> Unit) {
    val dark = isNvDark()
    val view = LocalView.current
    SideEffect {
        val w = (view.context as? Activity)?.window ?: return@SideEffect
        w.statusBarColor = Color.Transparent.toArgb()
        w.navigationBarColor = Color.Transparent.toArgb()
        WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !dark
        WindowCompat.getInsetsController(w, view).isAppearanceLightNavigationBars = !dark
    }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = NvTypography, content = content)
}

fun fmtBytes(b: Long): String {
    val df = DecimalFormat("#,##0.#")
    return when {
        b >= 1L shl 30 -> df.format(b / 1073741824.0) + " GB"
        b >= 1L shl 20 -> df.format(b / 1048576.0) + " MB"
        else -> df.format(b / 1024.0) + " KB"
    }
}

fun fmtDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val s = (ms / 1000L).toInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun folderLabel(name: String): String = when (name.trim().lowercase()) {
    "camera", "câmera", "camara" -> "Câmera"
    "screenshots", "screenshot", "capturas de tela", "capturas" -> "Capturas"
    "screen recordings", "screen recording", "screen recorder", "recordings" -> "Gravações"
    "whatsapp images", "whatsapp image" -> "WhatsApp"
    "whatsapp video", "whatsapp videos" -> "WhatsApp Vídeo"
    "telegram", "telegram images", "telegram video" -> "Telegram"
    "download", "downloads" -> "Downloads"
    "pictures", "imagens" -> "Imagens"
    "movies", "videos", "vídeos" -> "Vídeos"
    "dcim" -> "DCIM"
    "" -> "Sem pasta"
    else -> name
}
