package br.geracaoai.nuvem.data

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import br.geracaoai.nuvem.shizuku.Shiz
import java.io.File

/** O que ocupa o espaço do celular: categorias, apps (com os dados deles) e maiores arquivos. */
object Space {
    data class Summary(
        val total: Long, val free: Long,
        val apps: Long, val appsKnown: Boolean,
        val images: Long, val videos: Long, val audio: Long, val other: Long,
    ) {
        val used get() = (total - free).coerceAtLeast(0)
        val system get() = (used - apps - images - videos - audio - other).coerceAtLeast(0)
    }

    data class AppInfo(val pkg: String, val label: String, val bytes: Long, val dataBytes: Long, val sizeKnown: Boolean, val system: Boolean)
    data class Big(val path: String, val size: Long, val mtime: Long, val protected: Boolean) { val name get() = path.substringAfterLast('/') }

    /** "Acesso ao uso" liberado: dá pra saber o tamanho de cada app com os dados dele. */
    fun usageGranted(c: Context): Boolean = try {
        val ops = c.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    fun usageIntent(c: Context): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun apps(c: Context, includeSystem: Boolean = false): List<AppInfo> {
        val pm = c.packageManager
        val ssm = c.getSystemService(StorageStatsManager::class.java)
        val known = usageGranted(c)
        return pm.getInstalledApplications(0).asSequence()
            .filter { it.packageName != c.packageName }
            .map { ai ->
                val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                var bytes = 0L; var data = 0L; var ok = false
                if (known) try {
                    val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                    bytes = st.appBytes + st.dataBytes + st.cacheBytes; data = st.dataBytes + st.cacheBytes; ok = true
                } catch (_: Exception) {}
                if (!ok) bytes = try { File(ai.sourceDir).length() + (ai.splitSourceDirs?.sumOf { File(it).length() } ?: 0L) } catch (_: Exception) { 0L }
                AppInfo(ai.packageName, try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { ai.packageName }, bytes, data, ok, sys)
            }
            .filter { includeSystem || !it.system }
            .sortedByDescending { it.bytes }
            .toList()
    }

    fun summary(c: Context): Summary {
        val ssm = c.getSystemService(StorageStatsManager::class.java)
        val total = try { ssm.getTotalBytes(StorageManager.UUID_DEFAULT) } catch (_: Exception) { 0L }
        val free = try { ssm.getFreeBytes(StorageManager.UUID_DEFAULT) } catch (_: Exception) { 0L }
        var img = 0L; var vid = 0L; var aud = 0L; var oth = 0L
        try {
            val proj = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.SIZE)
            c.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), proj, null, null, null)?.use { k ->
                while (k.moveToNext()) {
                    val s = k.getLong(1).coerceAtLeast(0)
                    when (k.getInt(0)) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> img += s
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> vid += s
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> aud += s
                        else -> oth += s
                    }
                }
            }
        } catch (_: Exception) {}
        val known = usageGranted(c)
        val appsList = if (known) try { apps(c, includeSystem = true) } catch (_: Exception) { emptyList() } else emptyList()
        return Summary(total, free, appsList.sumOf { it.bytes }, known, img, vid, aud, oth)
    }

    /** Maiores arquivos (50 MB+): armazenamento público (inclui escondidos e Android/media) e, com Shizuku, Android/data. */
    suspend fun biggest(c: Context, limit: Int = 60): List<Big> {
        val min = 50L * 1024 * 1024
        val out = ArrayList<Big>()
        val root = LocalMedia.storageRoots().firstOrNull() ?: File("/storage/emulated/0")
        val stack = ArrayDeque<File>(); stack.add(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < 400_000) {
            val d = stack.removeFirst()
            val list = try { d.listFiles() } catch (_: Exception) { null } ?: continue
            for (f in list) {
                seen++
                if (f.isDirectory) {
                    if (d == root && f.name == "Android") { File(f, "media").takeIf { it.isDirectory }?.let { stack.addLast(it) }; continue }
                    stack.addLast(f)
                } else {
                    val len = try { f.length() } catch (_: Exception) { 0L }
                    if (len >= min) out += Big(f.absolutePath, len, f.lastModified(), false)
                }
            }
        }
        if (DeepScan.available()) try {
            val svc = Shiz.service()
            if (svc != null) {
                val data = root.absolutePath.trimEnd('/') + "/Android/data"
                var off = 0
                while (true) {
                    val page = svc.scan(data, min, "", off, 2000)
                    for (l in page) { val p = l.split('|', limit = 3); if (p.size == 3) out += Big(p[2], p[0].toLongOrNull() ?: 0, p[1].toLongOrNull() ?: 0, true) }
                    if (page.size < 2000) break
                    off += page.size
                }
            }
        } catch (e: Exception) { DiagLog.log("ESPAÇO", "Shizuku falhou nos maiores arquivos", e) }
        return out.sortedByDescending { it.size }.take(limit)
    }
}
