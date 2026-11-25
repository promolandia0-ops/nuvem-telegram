package br.geracaoai.nuvem.mega

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class MegaException(val code: Int, msg: String) : Exception(msg)

/** Arquivo ou pasta do MEGA, já com nome e chave descriptografados. */
data class MegaNode(
    val handle: String,
    val parent: String,
    val type: Int,          // 0 arquivo, 1 pasta, 2 raiz, 3 entrada, 4 lixeira
    val name: String,
    val size: Long,
    val ts: Long,           // segundos
    val key: ByteArray?,    // arquivo: 32 bytes (chave completa); pasta: 16 bytes
) {
    val isFile get() = type == 0
}

/**
 * Cliente mínimo do MEGA (protocolo público do site/SDK), só o que o app precisa:
 * entrar com e-mail e senha, listar arquivos, baixar descriptografando e mover para a lixeira do MEGA.
 * A senha nunca é guardada: fica só a sessão (sid) e a chave mestra.
 */
class MegaClient(var sid: String = "", var masterKey: ByteArray = ByteArray(0), var userHandle: String = "") {
    private var seq = Random.nextInt(0, Int.MAX_VALUE / 2)
    /** Avisos para a tela (ex.: verificação de segurança em andamento). */
    var onStatus: (String) -> Unit = {}

    // ---------- API ----------
    private fun api(cmd: JSONObject): Any {
        var wait = 500L
        var lastHttp = ""
        var hashcash: String? = null
        repeat(6) {
            val url = "https://g.api.mega.co.nz/cs?id=${seq++}" + if (sid.isNotBlank()) "&sid=$sid" else ""
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 60_000
                setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                setRequestProperty("User-Agent", "Nuvem/" + br.geracaoai.nuvem.BuildConfig.VERSION_NAME + " (Android)")
                hashcash?.let { setRequestProperty("X-Hashcash", it) }
            }
            try {
                conn.outputStream.use { it.write(JSONArray().put(cmd).toString().toByteArray()) }
                val code = conn.responseCode
                // 402 = o MEGA pede a prova de trabalho (hashcash) do protocolo dele; resolve e reenvia.
                if (code == 402 && hashcash == null) {
                    val challenge = conn.getHeaderField("X-Hashcash")
                    if (!challenge.isNullOrBlank()) {
                        onStatus("Verificação de segurança do MEGA… (pode levar até 1 minuto)")
                        hashcash = solveHashcash(challenge)
                        onStatus("")
                        return@repeat
                    }
                }
                if (code !in 200..299) {
                    val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
                    lastHttp = "HTTP $code" + (err?.trim()?.take(120)?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                    if (code in 500..599) { Thread.sleep(wait); wait = (wait * 2).coerceAtMost(8_000); return@repeat }
                    throw MegaException(-100, "O MEGA recusou o pedido ($lastHttp).")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                val parsed: Any = if (body.startsWith("[")) JSONArray(body).get(0) else body.toIntOrNull() ?: body
                if (parsed is Int && parsed == -3) { Thread.sleep(wait); wait *= 2; return@repeat } // EAGAIN
                if (parsed is Int && parsed < 0) throw MegaException(parsed, errorText(parsed))
                return parsed
            } finally { conn.disconnect() }
        }
        throw MegaException(-3, "O MEGA está ocupado" + (if (lastHttp.isNotBlank()) " ($lastHttp)" else "") + ". Tente de novo daqui a pouco.")
    }

    // ---------- Login ----------
    /** Entra na conta. [pin] é o código de 6 dígitos se a conta tiver verificação em duas etapas. */
    fun login(email: String, password: String, pin: String? = null) {
        val user = email.trim().lowercase()
        val pre = api(JSONObject().put("a", "us0").put("user", user)) as JSONObject
        val (pwKey, userHash) = if (pre.optInt("v") == 2) {
            val salt = b64d(pre.getString("s"))
            val spec = PBEKeySpec(password.toCharArray(), salt, 100_000, 256)
            val d = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
            d.copyOfRange(0, 16) to b64e(d.copyOfRange(16, 32))
        } else {
            val k = prepareKeyV1(password)
            k to stringHashV1(user, k)
        }
        val req = JSONObject().put("a", "us").put("user", user).put("uh", userHash)
        if (!pin.isNullOrBlank()) req.put("mfa", pin.trim())
        val r = api(req) as JSONObject
        val mk = aesEcbDecrypt(pwKey, b64d(r.getString("k")))
        masterKey = mk
        sid = when {
            r.has("csid") -> {
                val priv = aesEcbDecrypt(mk, b64d(r.getString("privk")))
                val mpis = readMpis(priv, 4) // p, q, d, u
                val n = mpis[0].multiply(mpis[1])
                val c = readMpis(b64d(r.getString("csid")), 1)[0]
                val m = c.modPow(mpis[2], n).toByteArray().let { if (it.isNotEmpty() && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
                b64e(m.copyOfRange(0, minOf(43, m.size)))
            }
            r.has("tsid") -> r.getString("tsid")
            else -> throw MegaException(-15, "O MEGA não devolveu a sessão.")
        }
        userHandle = r.optString("u", "")
        if (userHandle.isBlank()) userHandle = (api(JSONObject().put("a", "ug")) as JSONObject).optString("u")
    }

    // ---------- Arquivos ----------
    fun fetchNodes(): List<MegaNode> {
        val r = api(JSONObject().put("a", "f").put("c", 1).put("r", 1)) as JSONObject
        val arr = r.getJSONArray("f")
        val out = ArrayList<MegaNode>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.optInt("t")
            val h = o.getString("h")
            if (t >= 2) { out += MegaNode(h, o.optString("p"), t, when (t) { 2 -> "Meu Drive"; 3 -> "Entrada"; else -> "Lixeira" }, 0, o.optLong("ts"), null); continue }
            val key = nodeKey(o.optString("k")) ?: continue
            val attrKey = if (t == 0 && key.size >= 32) fileAesKey(key) else key.copyOfRange(0, 16)
            val name = try { decryptAttrName(o.optString("a"), attrKey) } catch (_: Exception) { null } ?: continue
            out += MegaNode(h, o.optString("p"), t, name, o.optLong("s"), o.optLong("ts"), key)
        }
        return out
    }

    private fun nodeKey(k: String): ByteArray? {
        if (k.isBlank()) return null
        for (part in k.split('/')) {
            val i = part.indexOf(':'); if (i <= 0) continue
            if (part.substring(0, i) != userHandle && k.contains("/")) continue
            return try { aesEcbDecrypt(masterKey, b64d(part.substring(i + 1))) } catch (_: Exception) { null }
        }
        return null
    }

    /** Baixa e descriptografa para [dest]. [onProgress] recebe bytes já baixados. */
    fun download(node: MegaNode, dest: File, onProgress: (Long) -> Unit, cancelled: () -> Boolean) {
        val key = node.key ?: throw MegaException(-14, "Arquivo sem chave")
        val r = api(JSONObject().put("a", "g").put("g", 1).put("ssl", 2).put("n", node.handle)) as JSONObject
        if (r.has("e") && r.optInt("e") < 0) throw MegaException(r.optInt("e"), errorText(r.optInt("e")))
        val url = r.getString("g")
        val iv = ByteArray(16).also { System.arraycopy(key, 16, it, 0, 8) }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(fileAesKey(key), "AES"), IvParameterSpec(iv))
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 30_000; readTimeout = 120_000 }
        try {
            val code = conn.responseCode
            if (code == 509) throw MegaException(-17, "Limite de transferência do MEGA atingido. Ele libera de novo em algumas horas; o app continua sozinho depois.")
            if (code !in 200..299) throw MegaException(-code, "O MEGA respondeu $code ao baixar.")
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        if (cancelled()) throw java.util.concurrent.CancellationException("cancelado")
                        val n = input.read(buf); if (n < 0) break
                        out.write(cipher.update(buf, 0, n))
                        total += n
                        onProgress(total)
                    }
                    cipher.doFinal()?.let { out.write(it) }
                }
            }
        } finally { conn.disconnect() }
    }

    /** Move para a lixeira do MEGA (dá pra recuperar lá, até esvaziar). */
    fun moveToTrash(handle: String, trashHandle: String) {
        api(JSONObject().put("a", "m").put("n", handle).put("t", trashHandle).put("i", randomId()))
    }

    // ---------- Cripto ----------
    companion object {
        fun b64d(s: String): ByteArray {
            var t = s.replace('-', '+').replace('_', '/').replace(",", "")
            while (t.length % 4 != 0) t += "="
            return Base64.decode(t, Base64.DEFAULT)
        }
        fun b64e(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

        fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray =
            Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")) }.doFinal(data)
        fun aesEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray =
            Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }.doFinal(data)

        /** Chave AES do arquivo: as duas metades da chave de 32 bytes em XOR. */
        fun fileAesKey(k: ByteArray): ByteArray = ByteArray(16) { (k[it].toInt() xor k[it + 16].toInt()).toByte() }

        fun decryptAttrName(a: String, key: ByteArray): String? {
            val raw = b64d(a)
            val dec = Cipher.getInstance("AES/CBC/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            }.doFinal(raw.copyOf(((raw.size + 15) / 16) * 16))
            val s = String(dec, Charsets.UTF_8).trimEnd('\u0000')
            if (!s.startsWith("MEGA{")) return null
            return JSONObject(s.substring(4)).optString("n").ifBlank { null }
        }

        private fun readMpis(b: ByteArray, count: Int): List<BigInteger> {
            val out = ArrayList<BigInteger>()
            var pos = 0
            repeat(count) {
                val bits = ((b[pos].toInt() and 0xff) shl 8) or (b[pos + 1].toInt() and 0xff)
                val len = (bits + 7) / 8
                out += BigInteger(1, b.copyOfRange(pos + 2, pos + 2 + len))
                pos += 2 + len
            }
            return out
        }

        private fun strToA32(s: ByteArray): IntArray {
            val padded = s.copyOf(((s.size + 3) / 4) * 4)
            val bb = ByteBuffer.wrap(padded)
            return IntArray(padded.size / 4) { bb.int }
        }
        private fun a32ToBytes(a: IntArray): ByteArray = ByteBuffer.allocate(a.size * 4).apply { a.forEach { putInt(it) } }.array()

        /** Contas antigas (v1): chave da senha com 65536 rodadas de AES. */
        fun prepareKeyV1(password: String): ByteArray {
            val arr = strToA32(password.toByteArray(Charsets.UTF_8))
            var pkey = a32ToBytes(intArrayOf(0x93C467E3.toInt(), 0x7DB0C7A4, 0xD1BE3F81.toInt(), 0x0152CB56))
            val keys = (arr.indices step 4).map { j -> a32ToBytes(IntArray(4) { i -> if (i + j < arr.size) arr[i + j] else 0 }) }
                .map { Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(it, "AES")) } }
            repeat(65536) { for (c in keys) pkey = c.doFinal(pkey) }
            return pkey
        }

        fun stringHashV1(email: String, key: ByteArray): String {
            val s32 = strToA32(email.toByteArray(Charsets.UTF_8))
            val h32 = IntArray(4)
            for (i in s32.indices) h32[i % 4] = h32[i % 4] xor s32[i]
            var h = a32ToBytes(h32)
            val c = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
            repeat(16384) { h = c.doFinal(h) }
            return b64e(h.copyOfRange(0, 4) + h.copyOfRange(8, 12))
        }

        /**
         * Prova de trabalho do MEGA (cabeçalho X-Hashcash "1:dificuldade:tempo:token"), como no SDK oficial:
         * procura um prefixo de 4 bytes cujo SHA-256 de (prefixo + token repetido 262144 vezes)
         * comece com um número abaixo do limite. Responde "1:token:prefixo".
         */
        fun solveHashcash(challenge: String): String {
            val parts = challenge.trim().split(':')
            if (parts.size < 4 || parts[0] != "1") throw MegaException(-100, "Desafio do MEGA em formato novo ($challenge).")
            val easiness = parts[1].toInt()
            val tokenStr = parts[3]
            val token = b64d(tokenStr)
            val threshold = ((((easiness and 63) shl 1) + 1).toLong() shl ((easiness shr 6) * 7 + 3)) and 0xFFFFFFFFL
            val buf = ByteArray(4 + 262144 * token.size)
            for (i in 0 until 262144) System.arraycopy(token, 0, buf, 4 + i * token.size, token.size)
            val md = java.security.MessageDigest.getInstance("SHA-256")
            var tries = 0L
            while (true) {
                val h = md.digest(buf)
                val v = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
                if (v <= threshold) return "1:$tokenStr:" + b64e(buf.copyOfRange(0, 4))
                // incrementa o prefixo (byte 0 primeiro, com vai-um)
                var j = 0
                while (j < 4) { buf[j] = (buf[j] + 1).toByte(); if (buf[j] != 0.toByte()) break; j++ }
                if (++tries > 50_000_000L) throw MegaException(-100, "Não consegui resolver a verificação do MEGA.")
            }
        }

        fun randomId(): String = (1..10).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")

        fun errorText(code: Int): String = when (code) {
            -9 -> "E-mail ou senha errados."
            -26 -> "Sua conta tem verificação em duas etapas. Digite o código de 6 dígitos."
            -16 -> "Conta do MEGA bloqueada."
            -17 -> "Limite de transferência do MEGA atingido. Tente mais tarde."
            -15 -> "Sessão do MEGA expirou. Entre de novo."
            -2 -> "Pedido inválido para o MEGA."
            -6 -> "Muitas tentativas. Espere um pouco."
            -4 -> "Muitas tentativas de login. Espere um pouco."
            else -> "Erro do MEGA ($code)."
        }
    }
}
