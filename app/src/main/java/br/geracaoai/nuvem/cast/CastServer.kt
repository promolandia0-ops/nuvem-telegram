package br.geracaoai.nuvem.cast

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import br.geracaoai.nuvem.tg.TdDataSource
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Servidorzinho HTTP no celular, só na rede de casa: a TV pede o vídeo aqui e o app vai buscando
 * os pedaços no Telegram conforme a TV assiste (pular pra frente também funciona).
 * O endereço tem um código aleatório por sessão; nada além do vídeo em transmissão é servido.
 */
object CastServer {
    data class Fonte(val fileId: Int, val size: Long, val mime: String, val nome: String, val localPath: String)

    @Volatile var fonte: Fonte? = null; private set
    @Volatile private var token = UUID.randomUUID().toString()
    @Volatile private var server: ServerSocket? = null
    /** A TV está puxando dados agora. */
    @Volatile var tvLendo = false; private set

    @Synchronized private fun garantir(): ServerSocket {
        server?.takeIf { !it.isClosed }?.let { return it }
        val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0)) }
        server = s
        thread(name = "nuvem-tv-http", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(name = "nuvem-tv-cli", isDaemon = true) { atender(c) }
            }
        }
        return s
    }

    /** Começa a servir [f] e devolve o endereço que a TV deve abrir (null = sem Wi-Fi). */
    fun servir(f: Fonte, soNoCelular: Boolean = false): String? {
        // Fora do Wi-Fi ainda dá pra abrir em outro app do próprio celular (MX Player etc.).
        val ip = ipLocal() ?: if (soNoCelular) "127.0.0.1" else return null
        token = UUID.randomUUID().toString()
        fonte = f
        val port = garantir().localPort
        val ext = f.nome.substringAfterLast('.', "mp4").lowercase().filter { it.isLetterOrDigit() }.take(5).ifBlank { "mp4" }
        return "http://$ip:$port/$token/video.$ext"
    }

    fun parar() {
        fonte = null; token = UUID.randomUUID().toString()
        runCatching { server?.close() }; server = null
    }

    /** IP do celular no Wi-Fi (ou no roteador do próprio celular). */
    fun ipLocal(): String? = try {
        val todos = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        val ord = todos.sortedBy { n -> when { n.name.startsWith("wlan") -> 0; n.name.startsWith("ap") || n.name.startsWith("swlan") -> 1; n.name.startsWith("eth") -> 2; else -> 3 } }
        ord.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }

    private fun atender(c: Socket) {
        try {
            c.soTimeout = 30_000
            val input = c.getInputStream()
            val head = lerCabecalho(input) ?: return
            val linhas = head.split("\r\n")
            val partes = linhas.first().split(" ")
            if (partes.size < 2) return
            val metodo = partes[0]; val caminho = partes[1]
            val hdr = linhas.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
            val out = BufferedOutputStream(c.getOutputStream(), 64 * 1024)
            val f = fonte
            if (f == null || !caminho.startsWith("/$token/") || f.size <= 0) { resposta(out, "404 Not Found", emptyMap()); return }
            if (metodo != "GET" && metodo != "HEAD") { resposta(out, "405 Method Not Allowed", emptyMap()); return }
            val total = f.size
            val r = faixa(hdr["range"], total)
            if (r == null) { resposta(out, "416 Range Not Satisfiable", mapOf("Content-Range" to "bytes */$total")); return }
            val (ini, fim) = r
            val len = fim - ini + 1
            val parcial = hdr["range"] != null
            val h = linkedMapOf(
                "Content-Type" to f.mime, "Content-Length" to len.toString(), "Accept-Ranges" to "bytes",
                "transferMode.dlna.org" to "Streaming",
                "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
                "Cache-Control" to "no-cache", "Connection" to "close",
            )
            if (parcial) h["Content-Range"] = "bytes $ini-$fim/$total"
            resposta(out, if (parcial) "206 Partial Content" else "200 OK", h, flush = metodo == "HEAD")
            if (metodo == "HEAD") return
            tvLendo = true
            enviar(f, ini, len, out)
            out.flush()
        } catch (_: SocketException) { // a TV fechou a conexão (pulo, pausa): normal
        } catch (e: Exception) { Log.w("NuvemTV", "conexão da TV: ${e.message}")
        } finally { tvLendo = false; runCatching { c.close() } }
    }

    @OptIn(UnstableApi::class)
    private fun enviar(f: Fonte, ini: Long, len: Long, out: java.io.OutputStream) {
        val buf = ByteArray(256 * 1024)
        var falta = len
        val local = File(f.localPath)
        if (f.localPath.isNotBlank() && local.isFile && local.length() >= ini + len) {
            RandomAccessFile(local, "r").use { raf ->
                raf.seek(ini)
                while (falta > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), falta).toInt()); if (n <= 0) break
                    out.write(buf, 0, n); falta -= n
                }
            }
            return
        }
        // Direto do Telegram, pedaço por pedaço (o mesmo leitor do player do app).
        val ds = TdDataSource(f.fileId, f.size)
        try {
            ds.open(DataSpec.Builder().setUri("tdlib://file/${f.fileId}").setPosition(ini).setLength(len).build())
            while (falta > 0) {
                val n = ds.read(buf, 0, minOf(buf.size.toLong(), falta).toInt()); if (n <= 0) break
                out.write(buf, 0, n); falta -= n
            }
        } finally { runCatching { ds.close() } }
    }

    private fun resposta(out: java.io.OutputStream, status: String, h: Map<String, String>, flush: Boolean = true) {
        val sb = StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
        if (!h.containsKey("Content-Length")) sb.append("Content-Length: 0\r\n")
        h.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Server: Nuvem/1.0 UPnP/1.0 DLNADOC/1.50\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (flush) out.flush()
    }

    private fun lerCabecalho(i: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 16_384) {
            val b = i.read(); if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.substring(0, sb.length - 4)
        }
        return null
    }

    /** Faixa pedida pela TV (bytes=ini-fim). null = inválida. */
    internal fun faixa(h: String?, total: Long): Pair<Long, Long>? {
        if (h.isNullOrBlank()) return 0L to total - 1
        val v = h.trim().removePrefix("bytes=").substringBefore(',')
        val a = v.substringBefore('-').trim(); val b = v.substringAfter('-', "").trim()
        val r = when {
            a.isNotEmpty() -> { val s = a.toLongOrNull() ?: return null; s to (b.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)) }
            b.isNotEmpty() -> { val n = b.toLongOrNull() ?: return null; (total - n).coerceAtLeast(0) to total - 1 }
            else -> return null
        }
        return if (r.first < 0 || r.first >= total || r.second < r.first) null else r
    }
}
