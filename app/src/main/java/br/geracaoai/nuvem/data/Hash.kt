package br.geracaoai.nuvem.data

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.min

object Hash {
    /** Janela do hash rápido: 1 MB no começo e 1 MB no fim, mais o tamanho. */
    const val QUICK_WINDOW = 1 shl 20

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        input.use { while (true) { val n = it.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Hash estável e barato: tamanho + primeiro 1 MB + último 1 MB. Prefixo `q:` para distinguir do SHA-256 completo. */
    fun fingerprint(size: Long, head: ByteArray, tail: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val len = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(size).array()
        md.update(len)
        md.update(head)
        md.update(tail)
        return "q:" + md.digest().joinToString("") { "%02x".format(it) }
    }

    fun quick(file: File): String {
        val size = file.length()
        if (size <= 0L) return fingerprint(0L, ByteArray(0), ByteArray(0))
        RandomAccessFile(file, "r").use { raf ->
            val headLen = min(QUICK_WINDOW.toLong(), size).toInt()
            val head = ByteArray(headLen)
            raf.seek(0)
            raf.readFully(head)
            if (size <= QUICK_WINDOW) return fingerprint(size, head, ByteArray(0))
            val tailLen = min(QUICK_WINDOW.toLong(), size).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(size - tailLen)
            raf.readFully(tail)
            return fingerprint(size, head, tail)
        }
    }
}
