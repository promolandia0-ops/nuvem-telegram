package br.geracaoai.nuvem.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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

/** Arquivo remoto de uma mensagem (foto maior, vídeo ou documento). */
data class RemoteFile(val file: TdApi.File, val name: String, val mime: String, val mini: ByteArray?, val isPhoto: Boolean)

object Repo {
    const val CHANNEL_TITLE = "Nuvem"

    fun docOf(m: TdApi.Message): TdApi.Document? = (m.content as? TdApi.MessageDocument)?.document

    fun captionOf(m: TdApi.Message): String? = when (val c = m.content) {
        is TdApi.MessageDocument -> c.caption?.text
        is TdApi.MessagePhoto -> c.caption?.text
        is TdApi.MessageVideo -> c.caption?.text
        is TdApi.MessageAnimation -> c.caption?.text
        else -> null
    }

    fun largestPhoto(photo: TdApi.Photo?): TdApi.PhotoSize? =
        photo?.sizes?.maxByOrNull { it.width.toLong() * it.height }

    fun thumbPhoto(photo: TdApi.Photo?): TdApi.PhotoSize? {
        val sizes = photo?.sizes ?: return null
        return sizes.filter { it.width in 160..800 }.minByOrNull { kotlin.math.abs(it.width - 320) }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }

    fun remoteOf(m: TdApi.Message): RemoteFile? = when (val c = m.content) {
        is TdApi.MessageDocument -> c.document?.document?.let {
            RemoteFile(it, c.document.fileName ?: "", c.document.mimeType ?: "", c.document.minithumbnail?.data, false)
        }
        is TdApi.MessagePhoto -> largestPhoto(c.photo)?.let { s ->
            RemoteFile(s.photo, "", "image/jpeg", c.photo.minithumbnail?.data, true)
        }
        is TdApi.MessageVideo -> c.video?.video?.let {
            RemoteFile(it, c.video.fileName ?: "", c.video.mimeType ?: "video/mp4", c.video.minithumbnail?.data, false)
        }
        is TdApi.MessageAnimation -> c.animation?.animation?.let {
            RemoteFile(it, c.animation.fileName ?: "", c.animation.mimeType ?: "", c.animation.minithumbnail?.data, false)
        }
        else -> null
    }

    fun fileOf(m: TdApi.Message): TdApi.File? = remoteOf(m)?.file

    fun thumbFileOf(m: TdApi.Message): TdApi.File? = when (val c = m.content) {
        is TdApi.MessageDocument -> c.document.thumbnail?.file
        is TdApi.MessageVideo -> c.video.thumbnail?.file
        is TdApi.MessageAnimation -> c.animation.thumbnail?.file
        is TdApi.MessagePhoto -> thumbPhoto(c.photo)?.photo
        else -> null
    }

    // ---------- Destino ----------
    /** Carrega a lista de chats. O 404 do TDLib significa "não há mais", não é erro. */
    suspend fun loadChatList() {
        repeat(8) {
            try { Td.send(TdApi.LoadChats(TdApi.ChatListMain(), 100), 15_000) }
            catch (e: TdException) { if (e.code == 404) return else return }
        }
    }

    suspend fun findNuvemChannels(): List<TdApi.Chat> {
        loadChatList()
        val ids = try { Td.send(TdApi.SearchChats().apply { query = CHANNEL_TITLE; limit = 50 }, 20_000).chatIds } catch (_: TdException) { longArrayOf() }
        return ids.toList().mapNotNull { id -> try { Td.send(TdApi.GetChat(id), 15_000) } catch (_: Exception) { null } }
            .filter { (it.type as? TdApi.ChatTypeSupergroup)?.isChannel == true && it.title.equals(CHANNEL_TITLE, true) }
    }
    suspend fun createChannel(): TdApi.Chat = Td.send(TdApi.CreateNewSupergroupChat().apply {
        title = CHANNEL_TITLE; isChannel = true; description = "Backup de fotos e vídeos (app Nuvem). Não apague."
    }, 30_000)
    suspend fun savedMessages(): TdApi.Chat { val me = Td.send(TdApi.GetMe(), 20_000); return Td.send(TdApi.CreatePrivateChat(me.id, false), 20_000) }
    fun useChat(c: TdApi.Chat) { Prefs.chatId = c.id; Prefs.chatTitle = c.title }

    /** Garante um destino válido: reabre o canal salvo, ou acha/cria o canal privado “Nuvem”. */
    suspend fun ensureDestination(): TdApi.Chat {
        loadChatList()
        val id = Prefs.chatId
        if (id != 0L) {
            try {
                val c = Td.send(TdApi.GetChat(id), 20_000)
                Prefs.chatTitle = c.title
                try { Td.send(TdApi.OpenChat(id), 15_000) } catch (_: Exception) {}
                return c
            } catch (e: Exception) {
                if (e is TdException && !ChatHistoryWalk.isMissingChat(e.code, e.message) && e.code != 408) throw e
            }
        }
        val existing = try { findNuvemChannels().firstOrNull() } catch (_: Exception) { null }
        if (existing != null) { useChat(existing); try { Td.send(TdApi.OpenChat(existing.id), 15_000) } catch (_: Exception) {}; return existing }
        val created = createChannel()
        useChat(created)
        try { Td.send(TdApi.OpenChat(created.id), 15_000) } catch (_: Exception) {}
        return created
    }

    // ---------- Índice da nuvem ----------
    fun toCloud(m: TdApi.Message): CloudItem? {
        val r = remoteOf(m) ?: return null
        val meta = Meta.parse(captionOf(m))
        val folder = if (meta?.original == true) ORIGINALS_FOLDER else (meta?.folder ?: "Outros")
        return CloudItem(m.id, m.chatId, meta?.name ?: r.name, meta?.mime?.ifBlank { null } ?: r.mime, meta?.size ?: r.file.size,
            meta?.taken?.takeIf { it > 0 } ?: (m.date * 1000L), folder, meta?.path ?: "", meta?.hash ?: "", r.mini)
    }

    /** Sincroniza o histórico do canal. Se [full], refaz tudo (detecta apagados). Nunca fica em loop infinito. */
    suspend fun syncIndex(full: Boolean = false, onProgress: ((Int) -> Unit)? = null) = withContext(Dispatchers.IO) {
        val dest = try { ensureDestination() } catch (e: Exception) {
            if (Prefs.chatId == 0L) return@withContext else throw e
        }
        val chat = dest.id
        if (full) Db.clearCloud(chat)
        var from = 0L; var emptyRounds = 0; var pages = 0
        while (true) {
            val r = try {
                Td.send(TdApi.GetChatHistory().apply { chatId = chat; fromMessageId = from; offset = 0; limit = 100; onlyLocal = false }, 20_000)
            } catch (e: TdException) {
                if (ChatHistoryWalk.isMissingChat(e.code, e.message)) {
                    ensureDestination()
                    break
                }
                // Canal novo/vazio às vezes responde vazio com atraso; trata timeout na 1ª página como vazio.
                if (e.code == 408 && from == 0L && pages == 0) {
                    val s = ChatHistoryWalk.next(from, emptyRounds, longArrayOf(), full, 0, pages)
                    emptyRounds = s.emptyRounds; pages = s.pages
                    if (s.done) break else continue
                }
                throw e
            }
            val msgs = r.messages ?: emptyArray()
            var known = 0
            for (m in msgs) {
                if (!full && Db.hasMsg(chat, m.id)) known++
                toCloud(m)?.let { Db.putCloud(it) }
            }
            if (msgs.isNotEmpty()) Db.bump()
            onProgress?.invoke(pages + 1)
            val s = ChatHistoryWalk.next(from, emptyRounds, msgs.map { it.id }.toLongArray(), full, known, pages)
            from = s.nextFromId; emptyRounds = s.emptyRounds; pages = s.pages
            if (s.done) break
        }
    }

    // ---------- Upload ----------
    fun localHash(i: LocalItem): String {
        Db.cachedHash(i.path, i.size, i.mtime)?.let { return it }
        val h = Hash.sha256(File(i.path).inputStream())
        Db.cacheHash(i.path, i.size, i.mtime, h); return h
    }

    fun thumbFor(c: Context, i: LocalItem): File? {
        if (i.mime.startsWith("video/")) videoThumb(i.path, c.cacheDir)?.let { return it }
        return try {
            val bmp = c.contentResolver.loadThumbnail(i.uri, Size(320, 320), null)
            val f = File(c.cacheDir, "thumb_${i.path.hashCode()}.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }; f
        } catch (_: Exception) { if (i.mime.startsWith("video/")) null else imageThumb(i.path, c.cacheDir) }
    }

    private fun imageThumb(path: String, dir: File): File? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 320).coerceAtLeast(1)
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val f = File(dir, "thumb_${path.hashCode()}.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }; f
        } catch (_: Exception) { null }
    }

    private fun videoThumb(path: String, dir: File): File? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val b = r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320) ?: r.frameAtTime ?: return null
            val f = File(dir, "vthumb_${path.hashCode()}.jpg")
            FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }; f
        } catch (_: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun imageSize(path: String): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        return (o.outWidth.coerceAtLeast(0)) to (o.outHeight.coerceAtLeast(0))
    }

    private fun inputThumb(thumb: File?): TdApi.InputThumbnail? {
        if (thumb == null || !thumb.exists()) return null
        val (w, h) = imageSize(thumb.absolutePath)
        return TdApi.InputThumbnail().apply {
            this.thumbnail = TdApi.InputFileLocal(thumb.absolutePath)
            width = w.takeIf { it > 0 } ?: 320
            height = h.takeIf { it > 0 } ?: 320
        }
    }

    /** Duração (s), largura e altura do vídeo, respeitando rotação. */
    fun videoInfo(path: String): Triple<Int, Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val dur = ((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt().coerceAtLeast(0)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val tmp = w; w = h; h = tmp }
            Triple(dur, w, h)
        } catch (_: Exception) { Triple(0, 0, 0) } finally { try { r.release() } catch (_: Exception) {} }
    }

    fun metaOf(i: LocalItem, hash: String, original: Boolean = false) =
        Meta(hash, i.size, i.taken, i.name, i.mime, i.bucket, i.relPath + i.name, original)

    /** Envia como foto ou vídeo (vídeo no arquivo original). Se a opção estiver ligada, a foto também sobe o original como documento. */
    suspend fun upload(c: Context, i: LocalItem, hash: String, onProgress: (Float) -> Unit): TdApi.Message {
        val thumb = thumbFor(c, i)
        try {
            val msg = uploadFile(i.path, i.size, metaOf(i, hash).toCaption(), thumb, onProgress, i.mime, c.cacheDir)
            uploadOriginalIfNeeded(c, i, hash, msg, onProgress)
            return msg
        } finally { thumb?.delete() }
    }

    /** Agrupa itens do mesmo álbum: mídia em lotes de até 10; documentos sozinhos. */
    fun chunksForAlbum(items: List<Pair<LocalItem, String>>): List<List<Pair<LocalItem, String>>> {
        val out = ArrayList<List<Pair<LocalItem, String>>>()
        val cur = ArrayList<Pair<LocalItem, String>>()
        fun flush() { if (cur.isNotEmpty()) { out += cur.toList(); cur.clear() } }
        for (p in items) {
            val kind = Meta.sendKind(p.first.mime.ifBlank { Meta.mimeFromName(p.first.name) }, p.first.size)
            if (kind == SendKind.DOCUMENT) { flush(); out += listOf(p) }
            else { cur += p; if (cur.size == 10) flush() }
        }
        flush(); return out
    }

    /** Envia um lote do mesmo álbum (álbum de mídia no Telegram, até 10). Se falhar, envia um a um. */
    suspend fun uploadGroup(c: Context, batch: List<Pair<LocalItem, String>>, onProgress: (Int, Float) -> Unit): List<TdApi.Message> {
        if (batch.size <= 1) {
            val (i, h) = batch.first(); return listOf(upload(c, i, h) { onProgress(0, it) })
        }
        val thumbs = batch.map { thumbFor(c, it.first) }
        try {
            val contents = batch.mapIndexed { idx, (i, h) ->
                val kind = Meta.sendKind(i.mime.ifBlank { Meta.mimeFromName(i.name) }, i.size)
                buildContent(kind, i.path, metaOf(i, h).toCaption(), thumbs[idx])
            }
            val started = try { startAlbum(contents) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
            val msgs = if (started == null) {
                return batch.mapIndexed { idx, (i, h) -> upload(c, i, h) { onProgress(idx, it) } }
            } else awaitAlbum(started, onProgress)
            batch.zip(msgs).forEachIndexed { idx, (pair, msg) ->
                uploadOriginalIfNeeded(c, pair.first, pair.second, msg) { onProgress(idx, it) }
            }
            return msgs
        } finally { thumbs.forEach { it?.delete() } }
    }

    /** Depois do último item do álbum, marca o nome no canal. */
    suspend fun sendAlbumMarker(album: String) {
        val chat = Prefs.chatId
        Td.send(TdApi.SendMessage().apply {
            chatId = chat
            inputMessageContent = TdApi.InputMessageText().apply { text = TdApi.FormattedText(Meta.albumTitle(album), emptyArray()) }
            options = TdApi.MessageSendOptions().apply { disableNotification = true }
        })
    }

    /** Se a foto foi enviada como mídia e a opção está ligada, envia o arquivo original (resposta). */
    suspend fun uploadOriginalIfNeeded(c: Context, i: LocalItem, hash: String, mediaMsg: TdApi.Message, onProgress: (Float) -> Unit) {
        if (!Prefs.originalPhotos) return
        if (mediaMsg.content !is TdApi.MessagePhoto) return
        if (Db.originalByHash(Prefs.chatId, hash) != null) return
        val thumb = thumbFor(c, i)
        try {
            uploadFile(i.path, i.size, metaOf(i, hash, original = true).toCaption(), thumb, onProgress, i.mime, c.cacheDir, mediaMsg.id, forceDocument = true)
        } finally { thumb?.delete() }
    }

    /** Envio genérico. Se a corrotina for cancelada (botão Parar), cancela o envio no TDLib e apaga a mensagem pendente. */
    suspend fun uploadFile(
        path: String,
        size: Long,
        caption: String,
        thumb: File?,
        onProgress: (Float) -> Unit,
        mime: String = "",
        cacheDir: File? = null,
        replyTo: Long = 0L,
        forceDocument: Boolean = false,
    ): TdApi.Message {
        val resolved = mime.ifBlank { Meta.mimeFromName(path) }
        val kind = if (forceDocument) SendKind.DOCUMENT else Meta.sendKind(resolved, size)
        var generated: File? = null
        try {
            val useThumb = thumb ?: if (kind == SendKind.VIDEO) {
                generated = videoThumb(path, cacheDir ?: File(path).parentFile ?: File("."))
                generated
            } else null
            return try {
                sendContent(buildContent(kind, path, caption, useThumb), onProgress, replyTo)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                if (kind == SendKind.DOCUMENT) throw e
                sendContent(buildContent(SendKind.DOCUMENT, path, caption, useThumb), onProgress, replyTo)
            }
        } finally { generated?.delete() }
    }

    private fun buildContent(kind: SendKind, path: String, caption: String, thumb: File?): TdApi.InputMessageContent {
        val cap = TdApi.FormattedText(caption, emptyArray())
        val th = inputThumb(thumb)
        return when (kind) {
            SendKind.PHOTO -> TdApi.InputMessagePhoto().apply {
                val (w, h) = imageSize(path)
                photo = TdApi.InputPhoto().apply {
                    photo = TdApi.InputFileLocal(path)
                    thumbnail = th
                    width = w; height = h
                }
                this.caption = cap
            }
            SendKind.VIDEO -> TdApi.InputMessageVideo().apply {
                val (dur, w, h) = videoInfo(path)
                video = TdApi.InputVideo().apply {
                    video = TdApi.InputFileLocal(path)
                    thumbnail = th
                    duration = dur; width = w; height = h
                    supportsStreaming = true
                }
                this.caption = cap
            }
            SendKind.DOCUMENT -> TdApi.InputMessageDocument().apply {
                document = TdApi.InputDocument().apply {
                    document = TdApi.InputFileLocal(path)
                    thumbnail = th
                    disableContentTypeDetection = true
                }
                this.caption = cap
            }
        }
    }

    private fun sendOpts() = TdApi.MessageSendOptions().apply { disableNotification = true }

    private suspend fun startAlbum(contents: List<TdApi.InputMessageContent>): Array<TdApi.Message> {
        val sent = Td.send(TdApi.SendMessageAlbum().apply { chatId = Prefs.chatId; inputMessageContents = contents.toTypedArray(); options = sendOpts() })
        val tmps = sent.messages
        if (tmps.isNullOrEmpty()) throw TdException(0, "Álbum vazio")
        return tmps
    }

    private suspend fun awaitAlbum(tmps: Array<TdApi.Message>, onProgress: (Int, Float) -> Unit): List<TdApi.Message> {
        val chat = Prefs.chatId
        val fileIds = tmps.mapNotNull { fileOf(it)?.id }.toSet()
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            Td.fileUpdates.collect { f ->
                if (f.id in fileIds && f.expectedSize > 0) onProgress(0, f.remote.uploadedSize.toFloat() / f.expectedSize)
            }
        }
        try {
            val out = ArrayList<TdApi.Message>(tmps.size)
            for (tmp in tmps) {
                val res = try { Td.awaitSend(tmp.id) }
                catch (e: kotlinx.coroutines.CancellationException) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        tmps.forEach { cancelSending(chat, it.id, fileOf(it)?.id ?: -1) }
                    }
                    throw e
                }
                val msg = res.message ?: throw TdException(res.error?.code ?: 0, res.error?.message ?: "Falha no envio do álbum")
                val r = remoteOf(msg) ?: throw TdException(0, "Mensagem sem mídia")
                if (!r.file.remote.isUploadingCompleted) throw TdException(0, "Upload incompleto")
                toCloud(msg)?.let { Db.putCloud(it); Db.bump() }
                out += msg
            }
            return out
        } finally { job.cancel() }
    }

    private suspend fun sendContent(content: TdApi.InputMessageContent, onProgress: (Float) -> Unit, replyTo: Long = 0L): TdApi.Message {
        val chat = Prefs.chatId
        val tmp = Td.send(TdApi.SendMessage().apply {
            chatId = chat; inputMessageContent = content; options = sendOpts()
            if (replyTo != 0L) this.replyTo = TdApi.InputMessageReplyToMessage().apply { messageId = replyTo }
        })
        val fileId = fileOf(tmp)?.id ?: -1
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            Td.fileUpdates.collect { f -> if (f.id == fileId && f.expectedSize > 0) onProgress(f.remote.uploadedSize.toFloat() / f.expectedSize) }
        }
        val res = try { Td.awaitSend(tmp.id) }
        catch (e: kotlinx.coroutines.CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) { cancelSending(chat, tmp.id, fileId) }
            throw e
        } finally { job.cancel() }
        val msg = res.message ?: throw TdException(res.error?.code ?: 0, res.error?.message ?: "Falha no envio")
        val r = remoteOf(msg) ?: throw TdException(0, "Mensagem sem mídia")
        if (!r.file.remote.isUploadingCompleted) throw TdException(0, "Upload incompleto")
        toCloud(msg)?.let { Db.putCloud(it); Db.bump() }
        return msg
    }

    /** Apagar a mensagem pendente faz o TDLib abortar o upload; depois libera o arquivo parcial. */
    suspend fun cancelSending(chat: Long, tmpMsgId: Long, fileId: Int) {
        try { Td.send(TdApi.DeleteMessages(chat, longArrayOf(tmpMsgId), true)) } catch (_: Exception) {}
        if (fileId > 0) {
            try { Td.send(TdApi.CancelPreliminaryUploadFile(fileId)) } catch (_: Exception) {}
            try { Td.send(TdApi.CancelDownloadFile(fileId, false)) } catch (_: Exception) {}
        }
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
                val ci = Db.originalByHash(chat, h) ?: Db.visibleByHash(chat, h) ?: Db.cloudByHash(chat, h) ?: return@filterIndexed false
                val m = Td.send(TdApi.GetMessage(chat, ci.msgId))
                val r = remoteOf(m) ?: return@filterIndexed false
                val meta = Meta.parse(captionOf(m))
                // Hash e tamanho originais vêm da legenda (foto comprimida pelo Telegram tem outro tamanho remoto).
                meta?.hash == h && meta.size == i.size && r.file.remote.isUploadingCompleted && r.file.remote.id.isNotEmpty()
            } catch (_: Exception) { false }
        }
    }

    // ---------- Arquivos da nuvem ----------
    suspend fun message(ci: CloudItem): TdApi.Message = Td.send(TdApi.GetMessage(ci.chatId, ci.msgId))

    suspend fun thumbPath(ci: CloudItem): String? = try {
        val t = thumbFileOf(message(ci))
        t?.let { Td.send(TdApi.DownloadFile(it.id, 8, 0, 0, true), 180_000).local.path.ifBlank { null } }
    } catch (_: Exception) { null }

    fun originalItem(ci: CloudItem): CloudItem? = if (ci.hash.isBlank()) null else Db.originalByHash(ci.chatId, ci.hash)

    suspend fun fullPath(ci: CloudItem): String? {
        val src = originalItem(ci) ?: ci
        val f = fileOf(message(src)) ?: return null
        return Td.send(TdApi.DownloadFile(f.id, 32, 0, 0, true), 0).local.path.ifBlank { null }
    }

    suspend fun deleteFromCloud(ci: CloudItem) {
        val orig = originalItem(ci)
        val ids = if (orig != null) longArrayOf(ci.msgId, orig.msgId) else longArrayOf(ci.msgId)
        Td.send(TdApi.DeleteMessages(ci.chatId, ids, true))
        Db.deleteCloud(ci.chatId, ci.msgId)
        if (orig != null) Db.deleteCloud(orig.chatId, orig.msgId)
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
