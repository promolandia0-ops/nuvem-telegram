package br.geracaoai.nuvem.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

object Prefs {
    private lateinit var p: SharedPreferences
    val raw get() = p
    val theme = MutableStateFlow(0) // 0 sistema, 1 claro, 2 escuro
    fun init(c: Context) { p = c.getSharedPreferences("nuvem", Context.MODE_PRIVATE); theme.value = p.getInt("theme", 0) }
    var apiId: Int get() = p.getInt("api_id", 0); set(v) = p.edit().putInt("api_id", v).apply()
    var apiHash: String get() = p.getString("api_hash", "")!!; set(v) = p.edit().putString("api_hash", v).apply()
    var chatId: Long get() = p.getLong("chat_id", 0); set(v) = p.edit().putLong("chat_id", v).apply()
    var chatTitle: String get() = p.getString("chat_title", "")!!; set(v) = p.edit().putString("chat_title", v).apply()
    var folders: Set<String> get() = p.getStringSet("folders", emptySet())!!; set(v) = p.edit().putStringSet("folders", v).apply()
    var autoBackup: Boolean get() = p.getBoolean("auto", true); set(v) = p.edit().putBoolean("auto", v).apply()
    var wifiOnly: Boolean get() = p.getBoolean("wifi", true); set(v) = p.edit().putBoolean("wifi", v).apply()
    var chargingOnly: Boolean get() = p.getBoolean("charging", false); set(v) = p.edit().putBoolean("charging", v).apply()
    var lastError: String get() = p.getString("err", "")!!; set(v) = p.edit().putString("err", v).apply()
    var lastBackup: Long get() = p.getLong("last", 0); set(v) = p.edit().putLong("last", v).apply()
    fun setTheme(v: Int) { p.edit().putInt("theme", v).apply(); theme.value = v }
}

/** Preferências da aba Assistidos e do controle de parada. */
object Prefs2 {
    private val p get() = Prefs.raw
    /** true = usuário apertou "Parar"; nada recomeça sozinho até "Retomar". */
    var backupPaused: Boolean get() = p.getBoolean("paused", false); set(v) = p.edit().putBoolean("paused", v).commit().let {}
    var watchedPaused: Boolean get() = p.getBoolean("wpaused", false); set(v) = p.edit().putBoolean("wpaused", v).commit().let {}
    var watchedEnabled: Boolean get() = p.getBoolean("wenabled", true); set(v) = p.edit().putBoolean("wenabled", v).apply()
    var watchedTree: String get() = p.getString("wtree", "")!!; set(v) = p.edit().putString("wtree", v).apply()
    var watchedLast: Long get() = p.getLong("wlast", 0); set(v) = p.edit().putLong("wlast", v).apply()
    var watchedError: String get() = p.getString("werr", "")!!; set(v) = p.edit().putString("werr", v).apply()
}
