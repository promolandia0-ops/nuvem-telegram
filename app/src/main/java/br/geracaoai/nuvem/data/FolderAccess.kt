package br.geracaoai.nuvem.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import java.io.File

object AllFilesAccess {
    fun granted(): Boolean = try { Environment.isExternalStorageManager() } catch (_: Exception) { false }
    fun intent(c: Context): Intent {
        val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        i.data = Uri.parse("package:${c.packageName}")
        return i
    }
}

object FolderTrees {
    private const val KEY = "folder_trees"
    fun uris(bucket: String): List<String> {
        if (bucket.isBlank()) return emptyList()
        return try {
            val j = org.json.JSONObject(Prefs.raw.getString(KEY, "{}") ?: "{}")
            val a = j.optJSONArray(bucket) ?: return emptyList()
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) { emptyList() }
    }
    fun add(bucket: String, uri: String) {
        if (bucket.isBlank() || uri.isBlank()) return
        try {
            val j = org.json.JSONObject(Prefs.raw.getString(KEY, "{}") ?: "{}")
            val a = j.optJSONArray(bucket) ?: org.json.JSONArray()
            val seen = (0 until a.length()).map { a.optString(it) }.toMutableSet()
            if (uri in seen) return
            a.put(uri)
            j.put(bucket, a)
            Prefs.raw.edit().putString(KEY, j.toString()).apply()
        } catch (_: Exception) {}
    }
}

object FolderAccess {
    fun findDirsNamed(bucket: String, maxDepth: Int = 5): List<File> {
        if (bucket.isBlank()) return emptyList()
        val want = bucket.trim('/').lowercase()
        val out = LinkedHashSet<File>()
        val roots = LocalMedia.storageRoots()
        val stack = ArrayDeque<Pair<File, Int>>()
        for (r in roots) stack.add(r to 0)
        var seen = 0
        while (stack.isNotEmpty() && seen < 8_000 && out.size < 40) {
            val (dir, depth) = stack.removeFirst()
            seen++
            val list = try { dir.listFiles() } catch (_: Exception) { null } ?: continue
            for (f in list) {
                if (!f.isDirectory) continue
                if (!DiscoverLogic.shouldEnterChild(dir.name, f.name, isWalkRoot = false)) continue
                if (f.name.equals(bucket, true) || f.name.lowercase() == want) out += f
                if (depth < maxDepth) stack.addLast(f to (depth + 1))
            }
        }
        return out.toList()
    }

    fun walkSaf(c: Context, tree: Uri, bucket: String): List<ListedFile> {
        val out = ArrayList<ListedFile>()
        val root = DocumentFile.fromTreeUri(c, tree) ?: return out
        val stack = ArrayDeque<DocumentFile>()
        stack.add(root)
        var n = 0
        while (stack.isNotEmpty() && n < 20_000) {
            val d = stack.removeFirst()
            val kids = try { d.listFiles() } catch (_: Exception) { emptyArray() }
            for (f in kids) {
                if (f.isDirectory) {
                    val name = f.name ?: continue
                    if (!DiscoverLogic.shouldEnterDir(name, isWalkRoot = false)) continue
                    stack.addLast(f)
                } else if (f.isFile) {
                    val name = f.name ?: continue
                    if (!BackupDiff.isMediaFile(name, f.type ?: "")) continue
                    val uri = f.uri.toString()
                    val path = safToPath(f.uri) ?: uri
                    val mime = f.type ?: if (name.lowercase().endsWith(".mp4")) "video/mp4" else "image/jpeg"
                    out += ListedFile(path, name, f.length(), f.lastModified(), mime, bucket, LocalMedia.relFromAbsolute(path), true, 0, f.lastModified())
                    n++
                }
            }
        }
        return out
    }

    fun safToPath(uri: Uri): String? {
        return try {
            val doc = DocumentsContract.getTreeDocumentId(uri).ifBlank { DocumentsContract.getDocumentId(uri) }
            val rel = doc.substringAfter(':', doc).trim('/')
            if (rel.isBlank()) null else "/storage/emulated/0/$rel"
        } catch (_: Exception) { null }
    }

    fun takeTree(c: Context, uri: Uri, bucket: String) {
        try {
            c.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {
            try { c.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        }
        FolderTrees.add(bucket, uri.toString())
        safToPath(uri)?.let { p ->
            val rel = LocalMedia.relFromAbsolute(if (p.endsWith('/')) p else "$p/")
            if (rel.isNotBlank()) FolderRoots.add(mapOf(bucket to setOf(if (rel.endsWith('/')) rel else "$rel/")))
            else FolderRoots.add(mapOf(bucket to setOf(p.trim('/') + "/")))
        }
    }
}
