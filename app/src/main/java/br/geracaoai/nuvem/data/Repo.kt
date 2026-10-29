package br.geracaoai.nuvem.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import br.geracaoai.nuvem.tg.Td
import br.geracaoai.nuvem.tg.TdException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream

data class Freeable(val items: List<LocalItem>, val bytes: Long)

object Repo {
    const val CHANNEL_TITLE = "Nuvem"

    fun docOf(m: TdApi.Message): TdApi.Document? = (m.content as? TdApi.MessageDocument)?.document
    fun captionOf(m: TdApi.Message): String? = (m.content as? TdApi.MessageDocument)?.caption?.text

    // ---------- Destino ----------
    suspend fun findNuvemChannels(): List<TdApi.Chat> {
        try { Td.send(TdApi.LoadChats(TdApi.ChatListMain(), 200)) } catch (_: TdException) {}
        val ids = Td.send(TdApi.SearchChats().apply { query = CHANNEL_TITLE; limit = 50 }).chatIds
        return ids.map { Td.send(TdApi.GetChat(it)) }.filter { (it.type as? TdApi.ChatTypeSupergroup)?.isChannel == true && it.title.equals(CHANNEL_TITLE, true) }
    }
    suspend fun createChannel(): TdApi.Chat = Td.send(TdApi.CreateNewSupergroupChat().apply {
        title = CHANNEL_TITLE; isChannel = true; description = "Backup de fotos e vídeos (app Nuvem). Não apague."
    })
    suspend fun savedMessages(): TdApi.Chat { val me = Td.send(TdApi.GetMe()); return Td.send(TdApi.CreatePrivateChat(me.id, false)) }
    fun useChat(c: TdApi.Chat) { Prefs.chatId = c.id; Prefs.chatTitle = c.title }

    // ---------- Índice da nuvem ----------
    fun toCloud(m: TdApi.Message): CloudItem? {
        val d = docOf(m) ?: return null
        val meta = Meta.parse(captionOf(m))
        return CloudItem(m.id, m.chatId, meta?.name ?: d.fileName, meta?.mime?.ifBlank { null } ?: d.mimeType, meta?.size ?: d.document.size,
            meta?.taken?.takeIf { it > 0 } ?: (m.date * 1000L), meta?.folder ?: "Outros", meta?.path ?: "", meta?.hash ?: "", d.minithumbnail?.data)
    }

    /** Sincroniza o histórico do canal. Se [full], refaz tudo (detecta apagados). */
    suspend fun syncIndex(full: Boolean = false) = withContext(Dispatchers.IO) {
        val chat = Prefs.chatId; if (chat == 0L) return@withContext
        try { Td.send(TdApi.OpenChat(chat)) } catch (_: Exception) {}
        if (full) Db.clearCloud(chat)
        var from = 0L; var emptyRounds = 0
        while (true) {
            val r = Td.send(TdApi.GetChatHistory().apply { chatId = chat; fromMessageId = from; offset = 0; limit = 100; onlyLocal = false })
            if (r.messages.isNullOrEmpty()) { if (++emptyRounds >= 2) break else continue }
            var known = 0
            for (m in r.messages) {
                if (!full && Db.hasMsg(chat, m.id)) known++
                toCloud(m)?.let { Db.putCloud(it) }
            }
            Db.bump()
            if (!full && known == r.messages.size) break
            from = r.messages.last().id
        }
    }

    // ---------- Upload ----------
    fun localHash(i: LocalItem): String {
        Db.cachedHash(i.path, i.size, i.mtime)?.let { return it }
        val h = Hash.sha256(File(i.path).inputStream())
        Db.cacheHash(i.path, i.size, i.mtime, h); return h
    }

    fun thumbFor(c: Context, i: LocalItem): File? = try {
        val bmp = c.contentResolver.loadThumbnail(i.uri, Size(320, 320), null)
        val f = File(c.cacheDir, "thumb_${i.path.hashCode()}.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }; f
    } catch (_: Exception) { null }

    /** Envia como documento (qualidade original). Retorna a mensagem final já verificada. */
    suspend fun upload(c: Context, i: LocalItem, hash: String, onProgress: (Float) -> Unit): TdApi.Message {
        val meta = Meta(hash, i.size, i.taken, i.name, i.mime, i.bucket, i.relPath + i.name)
        val thumb = thumbFor(c, i)
        val content = TdApi.InputMessageDocument().apply {
            document = TdApi.InputDocument().apply {
                document = TdApi.InputFileLocal(i.path)
                thumbnail = thumb?.let { TdApi.InputThumbnail().apply { this.thumbnail = TdApi.InputFileLocal(it.absolutePath); width = 320; height = 320 } }
                disableContentTypeDetection = true
            }
            caption = TdApi.FormattedText(meta.toCaption(), emptyArray())
        }
        val tmp = Td.send(TdApi.SendMessage().apply { chatId = Prefs.chatId; inputMessageContent = content; options = TdApi.MessageSendOptions().apply { disableNotification = true } })
        val fileId = docOf(tmp)?.document?.id ?: -1
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            Td.fileUpdates.collect { f -> if (f.id == fileId && f.expectedSize > 0) onProgress(f.remote.uploadedSize.toFloat() / f.expectedSize) }
        }
        val res = try { Td.awaitSend(tmp.id) } finally { job.cancel(); thumb?.delete() }
        val msg = res.message ?: throw TdException(res.error?.code ?: 0, res.error?.message ?: "Falha no envio")
        val d = docOf(msg) ?: throw TdException(0, "Mensagem sem documento")
        if (d.document.size != i.size || !d.document.remote.isUploadingCompleted) throw TdException(0, "Verificação de tamanho falhou")
        toCloud(msg)?.let { Db.putCloud(it); Db.bump() }
        return msg
    }

    // ---------- Liberar espaço ----------
    /** Estimativa rápida (usa hashes em cache): itens locais cujo hash+tamanho já estão na nuvem. */
    fun freeableQuick(c: Context): Freeable {
        val chat = Prefs.chatId; if (chat == 0L) return Freeable(emptyList(), 0)
        val items = LocalMedia.all(c).filter { i ->
            val h = Db.cachedHash(i.path, i.size, i.mtime) ?: return@filter false
            Db.cloudByHash(chat, h)?.size == i.size
        }
        return Freeable(items, items.sumOf { it.size })
    }

    /** Verificação rigorosa antes de apagar: recalcula hash do arquivo e confere a mensagem no Telegram. */
    suspend fun verifyForDelete(items: List<LocalItem>, onProgress: (Int) -> Unit): List<LocalItem> = withContext(Dispatchers.IO) {
        val chat = Prefs.chatId
        items.filterIndexed { idx, i ->
            onProgress(idx)
            try {
                val f = File(i.path); if (!f.exists() || f.length() != i.size) return@filterIndexed false
                val h = Hash.sha256(f.inputStream())
                val ci = Db.cloudByHash(chat, h) ?: return@filterIndexed false
                val m = Td.send(TdApi.GetMessage(chat, ci.msgId))
                val d = docOf(m) ?: return@filterIndexed false
                val meta = Meta.parse(captionOf(m))
                d.document.size == i.size && meta?.hash == h && d.document.remote.isUploadingCompleted && d.document.remote.id.isNotEmpty()
            } catch (_: Exception) { false }
        }
    }

    // ---------- Arquivos da nuvem ----------
    suspend fun message(ci: CloudItem): TdApi.Message = Td.send(TdApi.GetMessage(ci.chatId, ci.msgId))

    suspend fun thumbPath(ci: CloudItem): String? = try {
        val t = docOf(message(ci))?.thumbnail?.file
        t?.let { Td.send(TdApi.DownloadFile(it.id, 8, 0, 0, true)).local.path.ifBlank { null } }
    } catch (_: Exception) { null }

    suspend fun fullPath(ci: CloudItem): String? {
        val f = docOf(message(ci))?.document ?: return null
        return Td.send(TdApi.DownloadFile(f.id, 32, 0, 0, true)).local.path.ifBlank { null }
    }

    suspend fun deleteFromCloud(ci: CloudItem) {
        Td.send(TdApi.DeleteMessages(ci.chatId, longArrayOf(ci.msgId), true))
        Db.deleteCloud(ci.chatId, ci.msgId)
    }

    /** Restaura para o celular em Pictures/Nuvem ou Movies/Nuvem. */
    suspend fun restore(c: Context, ci: CloudItem): Boolean = withContext(Dispatchers.IO) {
        val src = fullPath(ci) ?: return@withContext false
        val video = ci.isVideo
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ci.name); put(MediaStore.MediaColumns.MIME_TYPE, ci.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Nuvem")
            put(MediaStore.MediaColumns.DATE_TAKEN, ci.taken); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = c.contentResolver.insert(coll, cv) ?: return@withContext false
        c.contentResolver.openOutputStream(uri)!!.use { o -> File(src).inputStream().use { it.copyTo(o) } }
        c.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        true
    }
}
