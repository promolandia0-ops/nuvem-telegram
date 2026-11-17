package br.geracaoai.nuvem.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

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
    /** Envia a foto (pra ver no Telegram) e também o arquivo original como documento. */
    var originalPhotos: Boolean get() = p.getBoolean("orig_photos", true); set(v) = p.edit().putBoolean("orig_photos", v).apply()
    var indexMsgId: Long get() = p.getLong("index_msg", 0); set(v) = p.edit().putLong("index_msg", v).apply()
    var legacyChatId: Long get() = p.getLong("legacy_chat", 0); set(v) = p.edit().putLong("legacy_chat", v).apply()
    var smartSort: Boolean get() = p.getBoolean("smart_sort", true); set(v) = p.edit().putBoolean("smart_sort", v).apply()
    var instantBackup: Boolean get() = p.getBoolean("instant", true); set(v) = p.edit().putBoolean("instant", v).apply()
    /** 0 = desligado. Depois de X dias, itens já confirmados na nuvem podem sair do celular. */
    var autoFreeDays: Int get() = p.getInt("auto_free_days", 0); set(v) = p.edit().putInt("auto_free_days", v).apply()
    var secretChatId: Long get() = p.getLong("secret_chat", 0); set(v) = p.edit().putLong("secret_chat", v).apply()
    var secretFolders: Set<String> get() = p.getStringSet("secret_folders", emptySet())!!; set(v) = p.edit().putStringSet("secret_folders", v).apply()
    var secretPinHash: String get() = p.getString("secret_pin", "")!!; set(v) = p.edit().putString("secret_pin", v).apply()
    var topicMap: String get() = p.getString("topic_map", "")!!; set(v) = p.edit().putString("topic_map", v).apply()
    fun galleryChats(): List<Long> = listOf(chatId, legacyChatId).filter { it != 0L }.distinct()
    fun indexId(chat: Long): Long {
        try {
            val j = JSONObject(p.getString("index_map", "") ?: "")
            if (j.has("$chat")) return j.optLong("$chat")
        } catch (_: Exception) {}
        return if (chat == chatId) indexMsgId else 0L
    }
    fun setIndexId(chat: Long, id: Long) {
        if (chat == chatId) indexMsgId = id
        val j = try { JSONObject(p.getString("index_map", "") ?: "{}") } catch (_: Exception) { JSONObject() }
        j.put("$chat", id)
        p.edit().putString("index_map", j.toString()).apply()
    }
    /** group | forum | channel | saved. Vazio = ainda não escolheu (padrão: grupo). */
    var destKind: String get() = p.getString("dest_kind", "")!!; set(v) = p.edit().putString("dest_kind", v).apply()
    /** Se true, alreadyOnCloud só considera o destino atual (reenviar o que está em outro chat). */
    var resendExisting: Boolean get() = p.getBoolean("resend_existing", false); set(v) = p.edit().putBoolean("resend_existing", v).apply()
    var knownChats: Set<String> get() = p.getStringSet("known_chats", emptySet())!!; set(v) = p.edit().putStringSet("known_chats", v).apply()
    fun rememberChat(id: Long) { if (id != 0L) knownChats = knownChats + id.toString() }
    fun destChats(): List<Long> = listOf(chatId, legacyChatId, secretChatId).filter { it != 0L }.distinct()
    fun allKnownChats(): List<Long> = (destChats() + knownChats.mapNotNull { it.toLongOrNull() }).filter { it != 0L }.distinct()
    fun clearDest() { if (chatId != 0L) rememberChat(chatId); chatId = 0; chatTitle = ""; indexMsgId = 0; topicMap = ""; destKind = destKind }
    fun clearAccount() { clearDest(); secretChatId = 0; secretFolders = emptySet(); knownChats = emptySet(); legacyChatId = 0 }
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
    var batteryGuideDismissed: Boolean get() = p.getBoolean("batt_guide_done", false); set(v) = p.edit().putBoolean("batt_guide_done", v).apply()
}
