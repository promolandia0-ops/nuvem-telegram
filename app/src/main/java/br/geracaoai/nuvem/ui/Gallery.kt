package br.geracaoai.nuvem.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.geracaoai.nuvem.data.CloudIndex
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.media.Thumbs
import br.geracaoai.nuvem.tg.CloudRec
import br.geracaoai.nuvem.tg.Tg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Galeria no estilo Google Fotos: grade por dia, filtros e abertura em tela cheia. */
@Composable
fun GalleryScreen(onOpen: (List<CloudRec>, Int) -> Unit) {
    val todos by CloudIndex.items.collectAsState()
    var filtro by rememberSaveable { mutableStateOf(0) }
    var atualizando by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun atualizar() {
        if (atualizando) return
        atualizando = true
        aviso = ""
        scope.launch {
            try {
                val destino = Tg.ensureDestination()
                val chats = (Prefs.destChats() + destino).distinct()
                val lista = withContext(Dispatchers.IO) { Tg.scanCloud(chats) }
                CloudIndex.replace(lista)
            } catch (e: Exception) {
                aviso = "Não consegui atualizar: ${e.message ?: "erro desconhecido"}"
            }
            atualizando = false
        }
    }
    LaunchedEffect(Unit) { if (todos.isEmpty()) atualizar() }

    val lista = remember(todos, filtro) {
        todos.filter { r ->
            when (filtro) {
                1 -> !r.meta.isVideo
                2 -> r.meta.isVideo
                else -> true
            }
        }.distinctBy { it.meta.hash }
    }
    val grupos = remember(lista) { lista.groupBy { rotuloDia(it.meta.taken) } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Fotos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { atualizar() }) {
                if (atualizando) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Refresh, "Atualizar")
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Tudo", "Fotos", "Vídeos").forEachIndexed { i, nome ->
                FilterChip(selected = filtro == i, onClick = { filtro = i }, label = { Text(nome) })
            }
        }
        if (aviso.isNotBlank()) {
            Text(
                aviso,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (lista.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (atualizando) "Carregando sua nuvem…" else "Nada na nuvem ainda.\nFaça o backup na aba Backup.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                grupos.forEach { (titulo, itens) ->
                    item(key = "dia-$titulo", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            titulo,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp),
                        )
                    }
                    items(count = itens.size, key = { itens[it].msgId }) { idx ->
                        val foto = itens[idx]
                        Tile(foto) { onOpen(lista, lista.indexOf(foto)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(foto: CloudRec, onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(initialValue = null, foto.msgId) { value = Thumbs.grid(foto) }
    Box(
        Modifier
            .aspectRatio(1f)
            .background(Color(0xFF2A2C33))
            .clickable(onClick = onClick),
    ) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = foto.meta.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (foto.meta.isVideo) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                if (foto.meta.duration > 0) {
                    Text(duracao(foto.meta.duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun rotuloDia(ms: Long): String {
    if (ms <= 0L) return "Sem data"
    val dia = Calendar.getInstance().apply { timeInMillis = ms }
    val hoje = Calendar.getInstance()
    val ontem = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun mesmoDia(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (mesmoDia(dia, hoje)) return "Hoje"
    if (mesmoDia(dia, ontem)) return "Ontem"
    return SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("pt-BR")).format(Date(ms))
}

private fun duracao(segundos: Int): String {
    val h = segundos / 3600
    val m = (segundos % 3600) / 60
    val s = segundos % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
