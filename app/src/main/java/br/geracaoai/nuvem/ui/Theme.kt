package br.geracaoai.nuvem.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import br.geracaoai.nuvem.data.Prefs
import java.text.DecimalFormat

@Composable
fun NuvemTheme(content: @Composable () -> Unit) {
    val t by Prefs.theme.collectAsState()
    val dark = when (t) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx), content = content)
}

fun fmtBytes(b: Long): String {
    val df = DecimalFormat("#,##0.#")
    return when { b >= 1L shl 30 -> df.format(b / 1073741824.0) + " GB"; b >= 1L shl 20 -> df.format(b / 1048576.0) + " MB"; else -> df.format(b / 1024.0) + " KB" }
}
