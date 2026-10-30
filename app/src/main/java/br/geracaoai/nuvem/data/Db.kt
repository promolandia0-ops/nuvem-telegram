package br.geracaoai.nuvem.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow

data class CloudItem(
    val msgId: Long, val chatId: Long, val name: String, val mime: String, val size: Long,
    val taken: Long, val folder: String, val path: String, val hash: String, val mini: ByteArray?,
) { val isVideo get() = mime.startsWith("video/") }

const val WATCHED_FOLDER = "Assistidos no Telegram"

object Db {
    private lateinit var h: SQLiteOpenHelper
    /** Incrementa sempre que o índice da nuvem muda (a UI observa). */
    val version = MutableStateFlow(0)

    fun init(c: Context) {
        h = object : SQLiteOpenHelper(c, "nuvem.db", null, 2) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE cloud(msg_id INTEGER, chat_id INTEGER, name TEXT, mime TEXT, size INTEGER, taken INTEGER, folder TEXT, path TEXT, hash TEXT, mini BLOB, PRIMARY KEY(chat_id,msg_id))")
                db.execSQL("CREATE INDEX cloud_hash ON cloud(hash)")
                db.execSQL("CREATE TABLE local_ok(path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, hash TEXT)")
                onUpgrade(db, 1, 2)
            }
            override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
                if (o < 2) db.execSQL("CREATE TABLE IF NOT EXISTS watched_seen(k TEXT PRIMARY KEY, at INTEGER)")
            }
        }
    }
    private val db get() = h.writableDatabase

    fun putCloud(i: CloudItem) {
        db.insertWithOnConflict("cloud", null, ContentValues().apply {
            put("msg_id", i.msgId); put("chat_id", i.chatId); put("name", i.name); put("mime", i.mime); put("size", i.size)
            put("taken", i.taken); put("folder", i.folder); put("path", i.path); put("hash", i.hash); put("mini", i.mini)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun bump() { version.value++ }
    fun deleteCloud(chatId: Long, msgId: Long) { db.delete("cloud", "chat_id=? AND msg_id=?", arrayOf("$chatId", "$msgId")); bump() }
    fun clearCloud(chatId: Long) { db.delete("cloud", "chat_id=?", arrayOf("$chatId")); bump() }
    fun hasMsg(chatId: Long, msgId: Long) = db.rawQuery("SELECT 1 FROM cloud WHERE chat_id=? AND msg_id=?", arrayOf("$chatId", "$msgId")).use { it.moveToFirst() }

    fun cloudByHash(chatId: Long, hash: String): CloudItem? = query("chat_id=? AND hash=?", arrayOf("$chatId", hash)).firstOrNull()
    /** Galeria normal (sem os vídeos assistidos no Telegram). */
    fun allCloud(chatId: Long): List<CloudItem> = query("chat_id=? AND folder<>?", arrayOf("$chatId", WATCHED_FOLDER))
    fun watchedCloud(chatId: Long): List<CloudItem> = query("chat_id=? AND folder=?", arrayOf("$chatId", WATCHED_FOLDER))

    /** Vídeos do cache do Telegram já processados (chave nome+tamanho). Fica mesmo após apagar da nuvem, para não reenviar. */
    fun seen(k: String) = db.rawQuery("SELECT 1 FROM watched_seen WHERE k=?", arrayOf(k)).use { it.moveToFirst() }
    fun markSeen(k: String) { db.insertWithOnConflict("watched_seen", null, ContentValues().apply { put("k", k); put("at", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE) }

    private fun query(where: String, args: Array<String>): List<CloudItem> =
        db.query("cloud", null, where, args, null, null, "taken DESC, msg_id DESC").use { c ->
            buildList { while (c.moveToNext()) add(CloudItem(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4),
                c.getLong(5), c.getString(6), c.getString(7), c.getString(8), c.getBlob(9))) }
        }

    /** Cache de hash de arquivos locais (path+tamanho+mtime) para não recalcular. */
    fun cachedHash(path: String, size: Long, mtime: Long): String? =
        db.rawQuery("SELECT hash FROM local_ok WHERE path=? AND size=? AND mtime=?", arrayOf(path, "$size", "$mtime")).use { if (it.moveToFirst()) it.getString(0) else null }
    fun cacheHash(path: String, size: Long, mtime: Long, hash: String) {
        db.insertWithOnConflict("local_ok", null, ContentValues().apply { put("path", path); put("size", size); put("mtime", mtime); put("hash", hash) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
