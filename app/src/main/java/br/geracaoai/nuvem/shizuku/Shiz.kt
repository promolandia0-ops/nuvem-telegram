package br.geracaoai.nuvem.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import br.geracaoai.nuvem.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class ShizState { NOT_RUNNING, NO_PERMISSION, READY }

object Shiz {
    @Volatile private var svc: IFileService? = null
    private val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, FileService::class.java.name))
        .daemon(false).processNameSuffix("files").debuggable(false).version(BuildConfig.VERSION_CODE)

    fun state(): ShizState = try {
        when {
            !Shizuku.pingBinder() -> ShizState.NOT_RUNNING
            Shizuku.isPreV11() -> ShizState.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizState.NO_PERMISSION
            else -> ShizState.READY
        }
    } catch (_: Throwable) { ShizState.NOT_RUNNING }

    fun requestPermission() { try { Shizuku.requestPermission(77) } catch (_: Throwable) {} }

    /** Espera o binder do Shizuku chegar (em processo novo, ex.: worker). */
    suspend fun awaitBinder(ms: Long = 4000): Boolean { var t = 0L; while (t < ms) { if (try { Shizuku.pingBinder() } catch (_: Throwable) { false }) return true; delay(200); t += 200 }; return false }

    suspend fun service(): IFileService? {
        svc?.let { if (it.asBinder().pingBinder()) return it }
        if (state() != ShizState.READY) return null
        val d = CompletableDeferred<IFileService?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName?, b: IBinder?) { val s = b?.takeIf { it.pingBinder() }?.let { IFileService.Stub.asInterface(it) }; svc = s; d.complete(s) }
            override fun onServiceDisconnected(n: ComponentName?) { svc = null }
        }
        return try { Shizuku.bindUserService(args, conn); withTimeoutOrNull(10_000) { d.await() } } catch (_: Throwable) { null }
    }
}
