package br.geracaoai.nuvem.work

import android.content.Context
import android.database.ContentObserver
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.MediaStore
import br.geracaoai.nuvem.data.FolderRoots
import br.geracaoai.nuvem.data.LocalMedia
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.data.Prefs2
import java.io.File

/** Observa a galeria e as pastas reais (CREATE/MOVED_TO) e dispara um backup com debounce. */
object MediaWatch {
    private var observer: ContentObserver? = null
    private val fileObs = ArrayList<FileObserver>()
    private val h = Handler(Looper.getMainLooper())

    fun start(c: Context) {
        val app = c.applicationContext
        if (observer != null) return
        val run = object : Runnable {
            override fun run() {
                if (!Prefs.instantBackup || Prefs2.backupPaused || Prefs.chatId == 0L) return
                if (!Prefs.backupAll && Prefs.folders.isEmpty() && Prefs.secretFolders.isEmpty() && Prefs.fileFolders.isEmpty()) return
                Backup.runNow(app, ignoreConstraints = false, expedited = true)
            }
        }
        observer = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                h.removeCallbacks(run)
                h.postDelayed(run, 2500)
            }
        }
        try {
            app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer!!)
            app.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer!!)
        } catch (_: Exception) {}
        watchFolders(app, run)
    }

    fun watchFolders(c: Context, run: Runnable? = null) {
        for (o in fileObs) try { o.stopWatching() } catch (_: Exception) {}
        fileObs.clear()
        val bounce = run ?: Runnable {
            if (!Prefs.instantBackup || Prefs2.backupPaused || Prefs.chatId == 0L) return@Runnable
            if (Prefs.folders.isEmpty() && Prefs.secretFolders.isEmpty()) return@Runnable
            Backup.runNow(c.applicationContext, ignoreConstraints = false, expedited = true)
        }
        val folders = Prefs.folders + Prefs.secretFolders
        for (b in folders) {
            for (dir in LocalMedia.folderDirs(b) + FolderRoots.forBucket(b).flatMap { rel -> LocalMedia.storageRoots().map { File(it, rel.trim('/')) } }) {
                if (!dir.isDirectory) continue
                try {
                    val fo = object : FileObserver(dir, FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.MOVED_FROM) {
                        override fun onEvent(event: Int, path: String?) {
                            h.removeCallbacks(bounce)
                            h.postDelayed(bounce, 2500)
                        }
                    }
                    fo.startWatching()
                    fileObs += fo
                } catch (_: Exception) {}
            }
        }
    }

    /** Varre as pastas no disco, pede rescan ao MediaStore e inicia o backup. */
    fun procurarNovosAgora(c: Context) {
        val app = c.applicationContext
        Thread {
            try {
                kotlinx.coroutines.runBlocking { br.geracaoai.nuvem.data.DiscoverStore.refresh(app) }
            } catch (_: Exception) {
                try {
                    val folders = Prefs.folders + Prefs.secretFolders
                    for (b in folders) LocalMedia.scanAndWalk(app, b)
                } catch (_: Exception) {}
            }
        }.start()
    }
}
