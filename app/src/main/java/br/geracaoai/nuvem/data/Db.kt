package br.geracaoai.nuvem.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow

data class CloudItem(
    val msgId: Long, val chatId: Long, val name: String, val mime: String, val size: Long,
    val taken: Long, val folder: String, val path: String, val hash: String, val mini: ByteArray?,
    val uniqueId: String = "",
) { val isVideo get() = mime.startsWith("video/") }

const val WATCHED_FOLDER = "Assistidos no Telegram"
const val ORIGINALS_FOLDER = "\u0001originais"

object Db {
    private lateinit var h: SQLiteOpenHelper
    /** Incrementa sempre que o índice da nuvem muda (a UI observa). */
    val version = MutableStateFlow(0)

    fun init(c: Context) {
        h = object : SQLiteOpenHelper(c, "nuvem.db", null, 3) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE cloud(msg_id INTEGER, chat_id INTEGER, name TEXT, mime TEXT, size INTEGER, taken INTEGER, folder TEXT, path TEXT, hash TEXT, mini BLOB, uniq TEXT, PRIMARY KEY(chat_id,msg_id))")
                db.execSQL("CREATE INDEX cloud_hash ON cloud(hash)")
                db.execSQL("CREATE TABLE local_ok(path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, hash TEXT)")
                onUpgrade(db, 1, 3)
            }
            override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
                if (o < 2) db.execSQL("CREATE TABLE IF NOT EXISTS watched_seen(k TEXT PRIMARY KEY, at INTEGER)")
                if (o < 3) try { db.execSQL("ALTER TABLE cloud ADD COLUMN uniq TEXT") } catch (_: Exception) {}
            }
        }
    }
    private val db get() = h.writableDatabase

    fun putCloud(i: CloudItem) {
        db.insertWithOnConflict("cloud", null, ContentValues().apply {
            put("msg_id", i.msgId); put("chat_id", i.chatId); put("name", i.name); put("mime", i.mime); put("size", i.size)
            put("taken", i.taken); put("folder", i.folder); put("path", i.path); put("hash", i.hash); put("mini", i.mini); put("uniq", i.uniqueId)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun bump() { version.value++ }
    fun deleteCloud(chatId: Long, msgId: Long) { db.delete("cloud", "chat_id=? AND msg_id=?", arrayOf("$chatId", "$msgId")); bump() }
    fun forgetMessages(chatId: Long, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        db.beginTransaction()
        try {
            for (id in ids) db.delete("cloud", "chat_id=? AND msg_id=?", arrayOf("$chatId", "$id"))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        bump()
    }
    fun idsForChat(chatId: Long): List<Long> =
        db.rawQuery("SELECT msg_id FROM cloud WHERE chat_id=?", arrayOf("$chatId")).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
    fun clearCloud(chatId: Long) { db.delete("cloud", "chat_id=?", arrayOf("$chatId")); bump() }
    fun hasMsg(chatId: Long, msgId: Long) = db.rawQuery("SELECT 1 FROM cloud WHERE chat_id=? AND msg_id=?", arrayOf("$chatId", "$msgId")).use { it.moveToFirst() }
    fun getCloud(chatId: Long, msgId: Long): CloudItem? = query("chat_id=? AND msg_id=?", arrayOf("$chatId", "$msgId"), includeMini = true).firstOrNull()
    /** Todos os itens do canal, inclusive originais e assistidos (para o índice). */
    fun allForIndex(chatId: Long): List<CloudItem> = query("chat_id=?", arrayOf("$chatId"))

    fun anyByNameSize(name: String, size: Long): CloudItem? {
        if (name.isBlank() || name == "item" || size <= 0) return null
        return query("name=? AND size=?", arrayOf(name, "$size")).firstOrNull()
    }
    fun anyByHash(hash: String, size: Long? = null): CloudItem? {
        if (hash.isBlank()) return null
        return if (size != null && size > 0) query("hash=? AND size=?", arrayOf(hash, "$size")).firstOrNull()
        else query("hash=?", arrayOf(hash)).firstOrNull()
    }
    fun anyByHashInChat(chatId: Long, hash: String, size: Long? = null): CloudItem? {
        if (hash.isBlank() || chatId == 0L) return null
        return if (size != null && size > 0) query("chat_id=? AND hash=? AND size=?", arrayOf("$chatId", hash, "$size")).firstOrNull()
        else query("chat_id=? AND hash=?", arrayOf("$chatId", hash)).firstOrNull()
    }
    fun anyByNameSizeInChat(chatId: Long, name: String, size: Long): CloudItem? {
        if (name.isBlank() || name == "item" || size <= 0 || chatId == 0L) return null
        return query("chat_id=? AND name=? AND size=?", arrayOf("$chatId", name, "$size")).firstOrNull()
    }
    fun allCloudMany(chatIds: List<Long>): List<CloudItem> =
        chatIds.filter { it != 0L }.distinct().flatMap { allCloud(it) }.sortedByDescending { it.taken }
    fun cloudByHash(chatId: Long, hash: String): CloudItem? = query("chat_id=? AND hash=?", arrayOf("$chatId", hash)).firstOrNull()
    fun visibleByHash(chatId: Long, hash: String): CloudItem? = query("chat_id=? AND hash=? AND folder<>?", arrayOf("$chatId", hash, ORIGINALS_FOLDER)).firstOrNull()
    fun originalByHash(chatId: Long, hash: String): CloudItem? = query("chat_id=? AND hash=? AND folder=?", arrayOf("$chatId", hash, ORIGINALS_FOLDER)).firstOrNull()
    /** Galeria normal (sem assistidos nem os documentos-originais das fotos). */
    fun allCloud(chatId: Long): List<CloudItem> = query("chat_id=? AND folder<>? AND folder<>?", arrayOf("$chatId", WATCHED_FOLDER, ORIGINALS_FOLDER))
    fun watchedCloud(chatId: Long): List<CloudItem> = query("chat_id=? AND folder=?", arrayOf("$chatId", WATCHED_FOLDER))

    /** Vídeos do cache do Telegram já processados (chave nome+tamanho). Fica mesmo após apagar da nuvem, para não reenviar. */
    fun seen(k: String) = db.rawQuery("SELECT 1 FROM watched_seen WHERE k=?", arrayOf(k)).use { it.moveToFirst() }
    fun markSeen(k: String) { db.insertWithOnConflict("watched_seen", null, ContentValues().apply { put("k", k); put("at", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE) }

    private val liteCols = arrayOf("msg_id", "chat_id", "name", "mime", "size", "taken", "folder", "path", "hash", "uniq")
    private fun query(where: String, args: Array<String>, includeMini: Boolean = false): List<CloudItem> {
        val cols = if (includeMini) null else liteCols
        return db.query("cloud", cols, where, args, null, null, "taken DESC, msg_id DESC").use { c ->
            val iMsg = c.getColumnIndex("msg_id"); val iChat = c.getColumnIndex("chat_id")
            val iName = c.getColumnIndex("name"); val iMime = c.getColumnIndex("mime")
            val iSize = c.getColumnIndex("size"); val iTaken = c.getColumnIndex("taken")
            val iFolder = c.getColumnIndex("folder"); val iPath = c.getColumnIndex("path")
            val iHash = c.getColumnIndex("hash"); val iMini = c.getColumnIndex("mini"); val iUniq = c.getColumnIndex("uniq")
            buildList {
                while (c.moveToNext()) add(CloudItem(
                    c.getLong(iMsg), c.getLong(iChat), c.getString(iName) ?: "", c.getString(iMime) ?: "",
                    c.getLong(iSize), c.getLong(iTaken), c.getString(iFolder) ?: "", c.getString(iPath) ?: "",
                    c.getString(iHash) ?: "", if (includeMini && iMini >= 0) c.getBlob(iMini) else null,
                    if (iUniq >= 0) c.getString(iUniq) ?: "" else "",
                ))
            }
        }
    }


    /** Cache de hash de arquivos locais (path+tamanho+mtime) para não recalcular. */
    fun cachedHash(path: String, size: Long, mtime: Long): String? =
        db.rawQuery("SELECT hash FROM local_ok WHERE path=? AND size=? AND mtime=?", arrayOf(path, "$size", "$mtime")).use { if (it.moveToFirst()) it.getString(0) else null }
    fun cacheHash(path: String, size: Long, mtime: Long, hash: String) {
        db.insertWithOnConflict("local_ok", null, ContentValues().apply { put("path", path); put("size", size); put("mtime", mtime); put("hash", hash) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
