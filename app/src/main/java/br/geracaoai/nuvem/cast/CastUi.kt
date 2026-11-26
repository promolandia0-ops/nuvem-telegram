package br.geracaoai.nuvem.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.geracaoai.nuvem.data.CloudItem
import kotlinx.coroutines.launch

/** Escolher a TV e mandar o item. */
@Composable
fun TransmitirDialog(ci: CloudItem, onFechar: () -> Unit, onComecou: () -> Unit = {}) {
    val ctx = LocalContext.current
    val desc = remember { Cast.descoberta(ctx) }
    val tvs by desc.devices.collectAsState()
    var procurando by remember { mutableStateOf(true) }
    var ip by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    var enviando by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        desc.scanFresh()
        kotlinx.coroutines.delay(5_000); procurando = false
    }
    fun mandar(tv: TvDevice) {
        enviando = tv.name; erro = null
        scope.launch {
            val e = Cast.transmitir(ctx, tv, ci)
            enviando = null
            if (e == null) { onComecou(); onFechar() } else erro = e
        }
    }
    AlertDialog(onFechar,
        icon = { Icon(Icons.Rounded.Cast, null) },
        title = { Text("Transmitir pra TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ci.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                if (CastServer.ipLocal() == null) Text("O celular não está no Wi-Fi. Conecte no mesmo Wi-Fi da TV.", color = MaterialTheme.colorScheme.error)
                tvs.forEach { tv ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = enviando == null) { mandar(tv) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Tv, null); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tv.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(tv.host, style = MaterialTheme.typography.labelSmall)
                        }
                        if (enviando == tv.name) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                if (procurando) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Procurando TVs no Wi-Fi…")
                } else if (tvs.isEmpty()) Text("Nenhuma TV achada. Ligue a TV, deixe no mesmo Wi-Fi e toque em Procurar de novo. Ou digite o IP da TV (Configurações → Rede na TV).",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(ip, { ip = it }, Modifier.weight(1f), singleLine = true, label = { Text("IP da TV (opcional)") })
                    TextButton({
                        erro = null; procurando = true
                        desc.manual(ip) { tv, msg -> procurando = false; if (tv == null) erro = msg else mandar(tv) }
                    }, enabled = ip.isNotBlank()) { Text("Usar") }
                }
                erro?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Funciona em TVs com DLNA (Samsung, LG, Philips, TCL, Sony e outras). O celular entrega o vídeo pra TV; pode apagar a tela.",
                    style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton({ procurando = true; desc.scanFresh(); scope.launch { kotlinx.coroutines.delay(5_000); procurando = false } }) { Text("Procurar de novo") } },
        dismissButton = { TextButton(onFechar) { Text("Fechar") } })
}

/** Controle remoto da transmissão (aparece enquanto está passando na TV). */
@Composable
fun CastPainel(modifier: Modifier = Modifier, compacto: Boolean = false) {
    val ctx = LocalContext.current
    val e by Cast.estado.collectAsState()
    val s = e ?: return
    var arrastando by remember { mutableStateOf<Float?>(null) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xEE101826)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CastConnected, null, tint = Color(0xFF7AB8FF)); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Na TV: ${s.tv.name}", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.titulo, color = Color(0xAAFFFFFF), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (s.carregando && s.erro == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
        }
        s.erro?.let { Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall) }
        if (!compacto && s.dur > 0) {
            Slider(arrastando ?: (s.pos.toFloat() / s.dur).coerceIn(0f, 1f), { arrastando = it },
                onValueChangeFinished = { arrastando?.let { Cast.irPara((it * s.dur).toLong()) }; arrastando = null })
            Row { Text(hms(arrastando?.let { (it * s.dur).toLong() } ?: s.pos), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f)); Text(hms(s.dur), color = Color.White, style = MaterialTheme.typography.labelSmall) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (!compacto) {
                IconButton({ Cast.volume(-5) }) { Icon(Icons.Rounded.VolumeDown, "Volume -", tint = Color.White) }
                IconButton({ Cast.pular(-10) }) { Icon(Icons.Rounded.Replay10, "Voltar 10s", tint = Color.White) }
            }
            FilledIconButton({ Cast.alternar() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Icon(if (s.tocando) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Tocar/Pausar")
            }
            if (!compacto) {
                IconButton({ Cast.pular(30) }) { Icon(Icons.Rounded.Forward30, "Avançar 30s", tint = Color.White) }
                IconButton({ Cast.volume(5) }) { Icon(Icons.Rounded.VolumeUp, "Volume +", tint = Color.White) }
            }
            IconButton({ Cast.parar(ctx) }) { Icon(Icons.Rounded.Stop, "Parar", tint = Color(0xFFFF8A80)) }
        }
    }
}

private fun hms(s: Long): String { val h = s / 3600; val m = s % 3600 / 60; val ss = s % 60; return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss) }
