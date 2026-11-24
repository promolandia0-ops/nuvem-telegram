package br.geracaoai.nuvem.data

import android.content.Context
import android.content.SharedPreferences

/** Mesmo arquivo e mesmas chaves da versão anterior: a conta, o destino e as configurações continuam. */
object Prefs {
    private lateinit var p: SharedPreferences

    fun init(c: Context) {
        p = c.getSharedPreferences("nuvem", Context.MODE_PRIVATE)
    }

    var apiId: Int
        get() = p.getInt("api_id", 0)
        set(v) = p.edit().putInt("api_id", v).apply()

    var apiHash: String
        get() = p.getString("api_hash", "") ?: ""
        set(v) = p.edit().putString("api_hash", v).apply()

    var chatId: Long
        get() = p.getLong("chat_id", 0L)
        set(v) = p.edit().putLong("chat_id", v).apply()

    var legacyChatId: Long
        get() = p.getLong("legacy_chat", 0L)
        set(v) = p.edit().putLong("legacy_chat", v).apply()

    var secretChatId: Long
        get() = p.getLong("secret_chat", 0L)
        set(v) = p.edit().putLong("secret_chat", v).apply()

    var autoBackup: Boolean
        get() = p.getBoolean("auto", true)
        set(v) = p.edit().putBoolean("auto", v).apply()

    var wifiOnly: Boolean
        get() = p.getBoolean("wifi", true)
        set(v) = p.edit().putBoolean("wifi", v).apply()

    var backupPaused: Boolean
        get() = p.getBoolean("paused", false)
        set(v) = p.edit().putBoolean("paused", v).apply()

    var iconsPurged: Boolean
        get() = p.getBoolean("icons_purged", false)
        set(v) = p.edit().putBoolean("icons_purged", v).apply()

    var lastBackup: Long
        get() = p.getLong("last", 0L)
        set(v) = p.edit().putLong("last", v).apply()

    var lastError: String
        get() = p.getString("err", "") ?: ""
        set(v) = p.edit().putString("err", v).apply()

    var lastLocal: Int
        get() = p.getInt("last_local", 0)
        set(v) = p.edit().putInt("last_local", v).apply()

    var lastCloud: Int
        get() = p.getInt("last_cloud", 0)
        set(v) = p.edit().putInt("last_cloud", v).apply()

    var lastPending: Int
        get() = p.getInt("last_pending", 0)
        set(v) = p.edit().putInt("last_pending", v).apply()

    /** Todos os destinos onde o app já guardou arquivos (para achar o que já está na nuvem). */
    fun destChats(): List<Long> = listOf(chatId, legacyChatId, secretChatId).filter { it != 0L }.distinct()
}
