package br.geracaoai.nuvem.shizuku

import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.system.exitProcess

/** Roda no processo do Shizuku (uid shell), que consegue ler Android/data de outros apps. */
class FileService : IFileService.Stub() {
    override fun destroy() { exitProcess(0) }

    override fun list(root: String, minSize: Long): MutableList<String> {
        val out = ArrayList<String>()
        val r = File(root); if (!r.exists()) return out
        r.walkTopDown().maxDepth(8).onEnter { d -> !d.name.equals("Stickers", true) && !d.name.startsWith(".thumb") }
            .forEach { f -> if (f.isFile && f.length() >= minSize && out.size < 5000) out += "${f.length()}|${f.lastModified()}|${f.absolutePath}" }
        return out
    }

    override fun open(path: String): ParcelFileDescriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun remove(path: String): Boolean = File(path).delete()
}
