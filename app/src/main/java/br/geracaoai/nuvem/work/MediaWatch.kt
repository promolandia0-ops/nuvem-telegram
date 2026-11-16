package br.geracaoai.nuvem.work

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import br.geracaoai.nuvem.data.Prefs
import br.geracaoai.nuvem.data.Prefs2

/** Observa a galeria e dispara um backup instantâneo (com debounce) quando entra mídia nova. */
object MediaWatch {
    private var observer: ContentObserver? = null
    private val h = Handler(Looper.getMainLooper())

    fun start(c: Context) {
        val app = c.applicationContext
        if (observer != null) return
        val run = object : Runnable {
            override fun run() {
                if (!Prefs.instantBackup || Prefs2.backupPaused || Prefs.chatId == 0L || Prefs.folders.isEmpty()) return
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
    }
}
