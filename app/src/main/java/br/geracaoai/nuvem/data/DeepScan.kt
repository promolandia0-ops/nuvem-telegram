package br.geracaoai.nuvem.data

import android.content.Context
import android.os.ParcelFileDescriptor
import br.geracaoai.nuvem.shizuku.ShizState
import br.geracaoai.nuvem.shizuku.Shiz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Caça profunda: fotos e vídeos que ficam em pastas protegidas (Android/data de outros apps),
 * como os "backups pré-edição" e a lixeira da Galeria Samsung. Só dá pra ler com o Shizuku
 * (igual o gerenciador de arquivos). Cada grupo vira um álbum que dá pra escolher pro backup.
 */
object DeepScan {
    const val PREFIX = "priv:"
    private const val EXTS = "jpg,jpeg,png,heic,heif,webp,gif,dng,mp4,mkv,mov,webm,m4v,3gp,avi"
    /** Fotos pequenas demais nessas pastas são miniaturas/ícones. */
    private const val MIN = 200L * 1024
    private val SKIP_PKG = listOf("org.telegram", "org.thunderdog", "br.geracaoai")

    data class PFile(val path: String, val size: Long, val mtime: Long) { val name get() = path.substringAfterLast('/') }
    data class Group(val key: String, val label: String, val trash: Boolean, val files: List<PFile>) {
        val bytes get() = files.sumOf { it.size }
    }

    /** Último resultado (null = ainda não procurou). */
    val groups = MutableStateFlow<List<Group>?>(null)
    val scanning = MutableStateFlow(false)

    fun available(): Boolean = Shiz.state() == ShizState.READY

    private fun dataRoot(): String = (LocalMedia.storageRoots().firstOrNull()?.absolutePath ?: "/storage/emulated/0").trimEnd('/') + "/Android/data"

    /** Procura em Android/data de todos os apps (via Shizuku). Devolve vazio se o Shizuku não estiver pronto. */
    suspend fun scan(c: Context): List<Group> = withContext(Dispatchers.IO) {
        if (!available()) return@withContext emptyList<Group>()
        scanning.value = true
        try {
            val svc = Shiz.service() ?: return@withContext emptyList<Group>()
            val root = dataRoot()
            val all = ArrayList<PFile>()
            var off = 0
            while (true) {
                val page = try { svc.scan(root, MIN, EXTS, off, 2000) } catch (e: Exception) { DiagLog.log("CAÇA", "Shizuku falhou ao listar", e); break }
                for (l in page) {
                    val p = l.split('|', limit = 3); if (p.size < 3) continue
                    all += PFile(p[2], p[0].toLongOrNull() ?: 0, p[1].toLongOrNull() ?: 0)
                }
                if (page.size < 2000) break
                off += page.size
            }
            val pm = c.packageManager
            val out = all.filter { f -> SKIP_PKG.none { f.path.removePrefix("$root/").startsWith(it) } }
                .groupBy { f ->
                    val rest = f.path.removePrefix("$root/")
                    val pkg = rest.substringBefore('/')
                    val after = rest.substringAfter("$pkg/").removePrefix("files/")
                    val sub = if (after.contains('/')) after.substringBefore('/') else ""
                    "$pkg/$sub"
                }.map { (k, files) ->
                    val pkg = k.substringBefore('/'); val sub = k.substringAfter('/')
                    val app = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg.substringAfterLast('.') }
                    val trash = sub.lowercase().contains("trash") || sub.lowercase().contains("recycle")
                    val label = when {
                        pkg == "com.sec.android.gallery3d" && trash -> "Lixeira da Galeria"
                        pkg == "com.sec.android.gallery3d" -> "Backups pré-edição (Galeria)"
                        trash -> "Lixeira • $app"
                        else -> "$app (protegido)"
                    }
                    Group(PREFIX + k, label, trash, files.sortedByDescending { it.size })
                }.sortedByDescending { it.bytes }
            DiagLog.log("CAÇA", "achou ${out.size} grupos protegidos, ${out.sumOf { it.files.size }} arquivos, ${out.sumOf { it.bytes } / (1024 * 1024)} MB")
            groups.value = out
            out
        } finally { scanning.value = false }
    }

    /** Copia um arquivo protegido pra uma área temporária (pra subir), via Shizuku. Quem chama apaga depois. */
    suspend fun stage(c: Context, f: PFile): File? = withContext(Dispatchers.IO) {
        val svc = Shiz.service() ?: return@withContext null
        val dir = File(c.cacheDir, "priv_stage").apply { mkdirs() }
        val out = File(dir, f.name)
        try {
            val pfd = svc.open(f.path)
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { i -> out.outputStream().use { i.copyTo(it, 256 * 1024) } }
            if (f.mtime > 0) out.setLastModified(f.mtime)
            out
        } catch (e: Exception) { DiagLog.log("CAÇA", "não copiou ${f.name}", e); out.delete(); null }
    }
}
