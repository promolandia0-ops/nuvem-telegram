package br.geracaoai.nuvem.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val GlassShape = RoundedCornerShape(24.dp)
val SoftShape = RoundedCornerShape(20.dp)

@Composable
fun AuroraBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), content = content)
}

/** Cartão liso (sem vidro): fundo sólido e borda fina, como apps de nuvem. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, radius: Dp = 24.dp, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(radius.coerceAtMost(16.dp))
    Column(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content
    )
}

@Composable
fun GlowIcon(icon: ImageVector, c1: Color, c2: Color, size: Dp = 52.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).clip(CircleShape).background(c1.copy(alpha = 0.28f)))
        Box(Modifier.size(size - 8.dp).clip(CircleShape).background(Brush.linearGradient(listOf(c1, c2))), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size((size.value * 0.42f).dp), tint = Color.White)
        }
    }
}

@Composable
fun StatusChip(text: String, tone: Color, pulsing: Boolean = false) {
    val a by rememberInfiniteTransition(label = "chip").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse"
    )
    val alpha = if (pulsing) a else 1f
    Row(
        Modifier.clip(CircleShape).background(tone.copy(alpha = 0.16f * alpha)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(tone.copy(alpha = alpha)))
        Text(text, color = tone, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PillButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true, enabled: Boolean = true) {
    val shape = RoundedCornerShape(999.dp)
    val bg = if (filled) Modifier.background(Nv.Aurora, shape) else Modifier.border(1.dp, Color.White.copy(0.2f), shape)
    Row(
        modifier.clip(shape).then(bg).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (filled) Color.White else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (filled) Color.White else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ProgressHero(fraction: Float, title: String, subtitle: String, running: Boolean, modifier: Modifier = Modifier) {
    val target = fraction.coerceIn(0f, 1f)
    val anim by animateFloatAsState(target, tween(900, easing = FastOutSlowInEasing), label = "hero")
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(if (running) 4000 else 16000, easing = LinearEasing)), label = "rot"
    )
    Box(modifier.size(148.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2 + 4.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val brush = Brush.sweepGradient(listOf(Nv.Cyan, Nv.Sky, Nv.Violet, Nv.Cyan), center = center)
            drawContext.canvas.save()
            drawContext.canvas.translate(center.x, center.y)
            drawContext.canvas.rotate(if (running) spin else -90f)
            drawContext.canvas.translate(-center.x, -center.y)
            drawArc(brush, 0f, 360f * anim, false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawContext.canvas.restore()
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 4.dp, vertical = 2.dp), style = MaterialTheme.typography.titleMedium, color = Nv.Sky, fontWeight = FontWeight.SemiBold)
}

@Composable
fun SettingSwitch(title: String, sub: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { on(!v) }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        AuroraSwitch(v, on)
    }
}

@Composable
fun AuroraSwitch(v: Boolean, on: (Boolean) -> Unit) {
    val t by animateFloatAsState(if (v) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "sw")
    Box(
        Modifier.size(width = 48.dp, height = 28.dp).clip(CircleShape)
            .background(if (v) Brush.horizontalGradient(listOf(Nv.Cyan, Nv.Sky)) else Brush.horizontalGradient(listOf(Color.White.copy(0.18f), Color.White.copy(0.12f))))
            .clickable { on(!v) }.padding(3.dp)
    ) {
        Box(Modifier.fillMaxHeight().aspectRatio(1f).align(Alignment.CenterStart).offset(x = (20.dp * t)).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun EmptyCloud(title: String, sub: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CloudIllustration()
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun CloudIllustration(modifier: Modifier = Modifier) {
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        -6f, 6f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y"
    )
    Canvas(modifier.size(140.dp, 110.dp).offset(y = bob.dp)) {
        val w = size.width; val h = size.height
        // moon
        drawCircle(Nv.Gold.copy(0.9f), 10.dp.toPx(), Offset(w * 0.82f, h * 0.18f))
        drawCircle(Nv.Night.copy(0.35f), 8.dp.toPx(), Offset(w * 0.85f, h * 0.16f))
        // stars
        listOf(Offset(w * 0.12f, h * 0.2f), Offset(w * 0.28f, h * 0.1f), Offset(w * 0.7f, h * 0.12f)).forEach {
            drawCircle(Color.White.copy(0.85f), 1.8.dp.toPx(), it)
        }
        val cloud = Path().apply {
            val y = h * 0.58f
            addOval(androidx.compose.ui.geometry.Rect(w * 0.18f, y - 28.dp.toPx(), w * 0.48f, y + 22.dp.toPx()))
            addOval(androidx.compose.ui.geometry.Rect(w * 0.36f, y - 42.dp.toPx(), w * 0.7f, y + 18.dp.toPx()))
            addOval(androidx.compose.ui.geometry.Rect(w * 0.52f, y - 26.dp.toPx(), w * 0.84f, y + 24.dp.toPx()))
        }
        drawPath(cloud, Brush.linearGradient(listOf(Color.White.copy(0.22f), Nv.Sky.copy(0.35f), Nv.Violet.copy(0.3f))))
        drawPath(cloud, Color.White.copy(0.35f), style = Stroke(1.6.dp.toPx()))
    }
}

@Composable
fun ShimmerBlock(modifier: Modifier) {
    val x by rememberInfiniteTransition(label = "sh").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "sx"
    )
    val brush = Brush.linearGradient(
        listOf(Color.White.copy(0.06f), Color.White.copy(0.16f), Color.White.copy(0.06f)),
        start = Offset(x * 400f - 200f, 0f), end = Offset(x * 400f + 80f, 200f)
    )
    Box(modifier.clip(SoftShape).background(brush))
}

@Composable
fun NvNavBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    data class Tab(val label: String, val icon: ImageVector, val id: Int)
    val tabs = listOf(
        Tab("Fotos", Icons.Rounded.Photo, 0),
        Tab("Vídeos", Icons.Rounded.VideoLibrary, 1),
        Tab("Álbuns", Icons.Rounded.PhotoAlbum, 2),
        Tab("Arquivos", Icons.Rounded.Folder, 3),
    )
    androidx.compose.material3.NavigationBar(modifier, containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        tabs.forEach { tab ->
            androidx.compose.material3.NavigationBarItem(
                selected = selected == tab.id,
                onClick = { onSelect(tab.id) },
                icon = { Icon(tab.icon, tab.label) },
                label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                ),
            )
        }
    }
}
