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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream

data class Freeable(val items: List<LocalItem>, val bytes: Long)

/** Arquivo de vídeo pronto para o player (id da sessão atual + tamanho real). */
data class Playable(
    val fileId: Int, val size: Long, val mime: String, val name: String,
    val uniqueId: String, val remoteId: String, val path: String, val complete: Boolean,
)

/** Arquivo remoto de uma mensagem (foto maior, vídeo ou documento). */
data class RemoteFile(val file: TdApi.File, val name: String, val mime: String, val mini: ByteArray?, val isPhoto: Boolean, val uniqueId: String = "")

object Repo {
    const val CHANNEL_TITLE = "Nuvem"
    private val reconcileLock = Mutex()
    @Volatile private var lastReconcileAt = 0L

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
            RemoteFile(it, c.document.fileName ?: "", c.document.mimeType ?: "", c.document.minithumbnail?.data, false, it.remote?.uniqueId ?: "")
        }
        is TdApi.MessagePhoto -> largestPhoto(c.photo)?.let { s ->
            RemoteFile(s.photo, "", "image/jpeg", c.photo.minithumbnail?.data, true, s.photo.remote?.uniqueId ?: "")
        }
        is TdApi.MessageVideo -> c.video?.video?.let {
            RemoteFile(it, c.video.fileName ?: "", c.video.mimeType ?: "video/mp4", c.video.minithumbnail?.data, false, it.remote?.uniqueId ?: "")
        }
        is TdApi.MessageAnimation -> c.animation?.animation?.let {
            RemoteFile(it, c.animation.fileName ?: "", c.animation.mimeType ?: "", c.animation.minithumbnail?.data, false, it.remote?.uniqueId ?: "")
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

    suspend fun findNuvemChats(): List<TdApi.Chat> {
        loadChatList()
        val ids = try { Td.send(TdApi.SearchChats().apply { query = CHANNEL_TITLE; limit = 50 }, 20_000).chatIds } catch (_: TdException) { longArrayOf() }
        return ids.toList().mapNotNull { id -> try { Td.send(TdApi.GetChat(id), 15_000) } catch (_: Exception) { null } }
            .filter { it.title.equals(CHANNEL_TITLE, true) && it.type is TdApi.ChatTypeSupergroup }
    }
    suspend fun findNuvemChannels(): List<TdApi.Chat> = findNuvemChats()
    /**
     * Cria um destino no Telegram tentando, nesta ordem: grupo com tópicos, grupo comum, canal.
     * Contas novas muitas vezes não podem criar canal, mas ainda criam grupo. Se tudo falhar,
     * o erro vem traduzido (restrição, flood, etc.).
     */
    suspend fun createSupergroup(title: String, description: String, welcome: Boolean = true): TdApi.Chat {
        data class Mode(val forum: Boolean, val channel: Boolean)
        val modes = listOf(Mode(true, false), Mode(false, false), Mode(false, true))
        var last: Exception? = null
        for (mode in modes) {
            try {
                val chat = Td.send(TdApi.CreateNewSupergroupChat().apply {
                    this.title = title; isForum = mode.forum; isChannel = mode.channel; this.description = description
                }, 30_000)
                try { Td.send(TdApi.OpenChat(chat.id), 15_000) } catch (_: Exception) {}
                if (welcome) try { sendWelcome(chat.id) } catch (_: Exception) {}
                return chat
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: TdException) {
                last = e
                if (e.code == 429) break
            } catch (e: Exception) { last = e }
        }
        throw TdException((last as? TdException)?.code ?: 0, describeCreateError(last ?: Exception("Não foi possível criar o destino.")))
    }

    private suspend fun sendWelcome(chatId: Long) {
        val tmp = Td.send(TdApi.SendMessage().apply {
            this.chatId = chatId
            inputMessageContent = TdApi.InputMessageText().apply { text = TdApi.FormattedText("☁️ Nuvem pronto", emptyArray()) }
            options = sendOpts()
        }, 20_000)
        try { Td.awaitSend(tmp.id) } catch (_: Exception) {}
    }

    suspend fun createForum(): TdApi.Chat = createDestination(DestKind.FORUM)
    suspend fun createChannel(): TdApi.Chat = createDestination(DestKind.CHANNEL)
    suspend fun createGroup(): TdApi.Chat = createDestination(DestKind.GROUP)
    suspend fun createSecretChat(): TdApi.Chat = createSupergroup("Nuvem Secreto", "Álbum secreto do app Nuvem. Não apague.")

    /** Cria exatamente o tipo pedido (grupo, grupo com tópicos, canal ou Mensagens Salvas). */
    suspend fun createDestination(kind: String): TdApi.Chat {
        Prefs.destKind = kind
        if (kind == DestKind.SAVED) return savedMessages().also { Prefs.destKind = DestKind.SAVED }
        val desc = "Backup de fotos e vídeos (app Nuvem). Não apague."
        val forum = kind == DestKind.FORUM
        val channel = kind == DestKind.CHANNEL
        return try {
            val chat = Td.send(TdApi.CreateNewSupergroupChat().apply {
                title = CHANNEL_TITLE; isForum = forum; isChannel = channel; description = desc
            }, 30_000)
            try { Td.send(TdApi.OpenChat(chat.id), 15_000) } catch (_: Exception) {}
            try { sendWelcome(chat.id) } catch (_: Exception) {}
            chat
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            throw TdException((e as? TdException)?.code ?: 0, describeCreateError(e))
        }
    }
    suspend fun savedMessages(): TdApi.Chat { val me = Td.send(TdApi.GetMe(), 20_000); return Td.send(TdApi.CreatePrivateChat(me.id, false), 20_000) }
    fun useChat(c: TdApi.Chat) {
        if (Prefs.chatId != 0L && Prefs.chatId != c.id) {
            Prefs.rememberChat(Prefs.chatId)
            if (Prefs.legacyChatId == 0L) Prefs.legacyChatId = Prefs.chatId
            Prefs.indexMsgId = 0; Prefs.topicMap = ""
        }
        Prefs.chatId = c.id; Prefs.chatTitle = c.title
        Prefs.rememberChat(c.id)
    }

    /** Garante um destino válido: reabre o salvo, ou acha/cria o grupo “Nuvem” com tópicos. */
    suspend fun ensureDestination(): TdApi.Chat {
        loadChatList()
        val id = Prefs.chatId
        var chat: TdApi.Chat? = null
        if (id != 0L) {
            try {
                chat = Td.send(TdApi.GetChat(id), 20_000)
                Prefs.chatTitle = chat.title
                try { Td.send(TdApi.OpenChat(id), 15_000) } catch (_: Exception) {}
            } catch (e: Exception) {
                if (e is TdException && !ChatHistoryWalk.isMissingChat(e.code, e.message) && e.code != 408) throw e
            }
        }
        if (chat == null) {
            val existing = try { findNuvemChats().firstOrNull() } catch (_: Exception) { null }
            if (existing != null) {
                useChat(existing); try { Td.send(TdApi.OpenChat(existing.id), 15_000) } catch (_: Exception) {}
                chat = existing
            } else {
                val created = createDestination(DestKind.effective())
                useChat(created)
                try { Td.send(TdApi.OpenChat(created.id), 15_000) } catch (_: Exception) {}
                chat = created
            }
        }
        return Dest.prepare(chat!!)
    }

    suspend fun ensureSecretDestination(): TdApi.Chat {
        val id = Prefs.secretChatId
        if (id != 0L) {
            try {
                val c = Td.send(TdApi.GetChat(id), 20_000)
                try { Td.send(TdApi.OpenChat(id), 15_000) } catch (_: Exception) {}
                val type = c.type as? TdApi.ChatTypeSupergroup
                if (type != null && !type.isChannel) {
                    try {
                        val sg = Td.send(TdApi.GetSupergroup(type.supergroupId), 15_000)
                        if (!sg.isForum) Td.send(TdApi.ToggleSupergroupIsForum(type.supergroupId, true, true), 20_000)
                    } catch (_: Exception) {}
                }
                return c
            } catch (e: Exception) {
                if (e is TdException && !ChatHistoryWalk.isMissingChat(e.code, e.message) && e.code != 408) throw e
            }
        }
        val created = createSecretChat()
        Prefs.secretChatId = created.id
        try { Td.send(TdApi.OpenChat(created.id), 15_000) } catch (_: Exception) {}
        return created
    }

    // ---------- Índice da nuvem ----------
    fun isIndexMessage(m: TdApi.Message): Boolean {
        val d = (m.content as? TdApi.MessageDocument)?.document ?: return false
        return Meta.isIndexName(d.fileName)
    }

    fun toCloud(m: TdApi.Message): CloudItem? {
        if (isIndexMessage(m)) return null
        val r = remoteOf(m) ?: return null
        val meta = Meta.parse(captionOf(m))
        val known = if (meta == null) Db.getCloud(m.chatId, m.id) else null
        if (meta == null && known != null && known.hash.isNotBlank()) {
            return known.copy(mini = known.mini ?: r.mini, uniqueId = known.uniqueId.ifBlank { r.uniqueId })
        }
        val folder = when {
            meta?.original == true -> ORIGINALS_FOLDER
            !meta?.folder.isNullOrBlank() -> meta!!.folder
            known != null && known.folder.isNotBlank() -> known.folder
            else -> "Outros"
        }
        return CloudItem(
            m.id, m.chatId,
            meta?.name?.ifBlank { null } ?: known?.name?.ifBlank { null } ?: r.name.ifBlank { "item" },
            meta?.mime?.ifBlank { null } ?: known?.mime?.ifBlank { null } ?: r.mime,
            meta?.size?.takeIf { it > 0 } ?: known?.size?.takeIf { it > 0 } ?: r.file.size,
            meta?.taken?.takeIf { it > 0 } ?: known?.taken?.takeIf { it > 0 } ?: (m.date * 1000L),
            folder,
            meta?.path?.ifBlank { null } ?: known?.path ?: "",
            meta?.hash?.ifBlank { null } ?: known?.hash ?: "",
            r.mini ?: known?.mini,
            r.uniqueId.ifBlank { known?.uniqueId ?: "" },
        )
    }

    fun remember(msg: TdApi.Message, meta: Meta) {
        val r = remoteOf(msg)
        val folder = if (meta.original) ORIGINALS_FOLDER else meta.folder.ifBlank { "Outros" }
        Db.putCloud(CloudItem(msg.id, msg.chatId, meta.name, meta.mime.ifBlank { r?.mime ?: "" }, meta.size,
            meta.taken, folder, meta.path, meta.hash, r?.mini, r?.uniqueId ?: ""))
        Db.bump()
    }

    /** Sincroniza o histórico do destino (e do canal antigo, se houver). Se [full], refaz tudo. */
    suspend fun syncIndex(full: Boolean = false, onProgress: ((Int) -> Unit)? = null) = withContext(Dispatchers.IO) {
        val dest = try { ensureDestination() } catch (e: Exception) {
            if (Prefs.chatId == 0L) return@withContext else throw e
        }
        val chats = (listOf(dest.id) + Prefs.allKnownChats()).filter { it != 0L }.distinct()
        for (chat in chats) {
            if (full) Db.clearCloud(chat)
            try { pullIndex(chat) } catch (_: Exception) {}
            val topics = try { Topics.list(chat) } catch (_: Exception) { emptyList() }
            if (topics.isNotEmpty()) {
                for (tp in topics) walkMessages(chat, full, tp.forumTopicId, onProgress)
            } else walkMessages(chat, full, 0, onProgress)
        }
    }

    private suspend fun walkMessages(chat: Long, full: Boolean, topicId: Int, onProgress: ((Int) -> Unit)?) {
        var from = 0L; var emptyRounds = 0; var pages = 0
        while (true) {
            val r = try {
                if (topicId != 0) Td.send(TdApi.GetForumTopicHistory(chat, topicId, from, 0, 100), 20_000)
                else Td.send(TdApi.GetChatHistory().apply { chatId = chat; fromMessageId = from; offset = 0; limit = 100; onlyLocal = false }, 20_000)
            } catch (e: TdException) {
                if (ChatHistoryWalk.isMissingChat(e.code, e.message)) break
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
        if (i.mediaId > 0) Db.cachedHash("mid:${i.mediaId}", i.size, i.mtime)?.let { h ->
            Db.cacheHash(i.path, i.size, i.mtime, h)
            return h
        }
        val h = Hash.quick(File(i.path))
        Db.cacheHash(i.path, i.size, i.mtime, h)
        if (i.mediaId > 0) Db.cacheHash("mid:${i.mediaId}", i.size, i.mtime, h)
        return h
    }

    fun thumbFor(c: Context, i: LocalItem): File? {
        if (i.mime.startsWith("video/")) videoThumb(i.path, c.cacheDir)?.let { return it }
        return try {
            val bmp = c.contentResolver.loadThumbnail(i.uri, Size(320, 320), null)
            val f = File(c.cacheDir, "thumb_${i.path.hashCode()}.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }; f
        } catch (_: Exception) { if (i.mime.startsWith("video/")) null else imageThumb(i.path, c.cacheDir) }
    }

    private fun safeCacheDir(dir: File): File {
        val p = dir.absolutePath
        if ("/DCIM/" in p || "/Pictures/" in p || "/Movies/" in p || "/Download/" in p) return try { Td.cacheDir() } catch (_: Exception) { dir }
        return dir
    }

    private fun imageThumb(path: String, dir: File): File? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 320).coerceAtLeast(1)
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val f = File(safeCacheDir(dir), "thumb_${path.hashCode()}.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }; f
        } catch (_: Exception) { null }
    }

    private fun videoThumb(path: String, dir: File): File? {
        val r = MediaMetadataRetriever()
        val outDir = safeCacheDir(dir)
        return try {
            r.setDataSource(path)
            val b = r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320) ?: r.frameAtTime ?: return null
            val f = File(outDir, "vthumb_${path.hashCode()}.jpg")
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

    fun mediaHints(i: LocalItem): Triple<Long, Int, Int> {
        return if (i.mime.startsWith("video/") || Meta.mimeFromName(i.name).startsWith("video/")) {
            val (dur, w, h) = videoInfo(i.path)
            Triple(dur * 1000L, w, h)
        } else {
            val (w, h) = imageSize(i.path)
            Triple(0L, w, h)
        }
    }

    fun captionFor(i: LocalItem, folder: String, original: Boolean = false) =
        Classify.shortCaption(i.taken, folder, original)

    /** Envia como foto ou vídeo (vídeo no arquivo original). Originais de foto sobem depois, em álbum de documentos. */
    suspend fun upload(c: Context, i: LocalItem, hash: String, onProgress: (Float) -> Unit, chatId: Long = Prefs.chatId, topicId: Int = 0, folder: String = i.bucket): TdApi.Message {
        val thumb = thumbFor(c, i)
        try {
            return uploadFile(i.path, i.size, captionFor(i, folder), thumb, onProgress, i.mime, c.cacheDir, meta = metaOf(i, hash), chatId = chatId, topicId = topicId)
        } finally { thumb?.delete() }
    }

    /**
     * Agrupa na ordem recebida (já deve estar por data): mídia (foto+vídeo) em lotes de até 10
     * e documentos em lotes de até 10, sem misturar os dois tipos no mesmo grupo.
     */
    fun chunksForAlbum(items: List<Pair<LocalItem, String>>): List<List<Pair<LocalItem, String>>> {
        val out = ArrayList<List<Pair<LocalItem, String>>>()
        val media = ArrayList<Pair<LocalItem, String>>()
        val docs = ArrayList<Pair<LocalItem, String>>()
        fun flushMedia() { if (media.isNotEmpty()) { out += media.toList(); media.clear() } }
        fun flushDocs() { if (docs.isNotEmpty()) { out += docs.toList(); docs.clear() } }
        for (p in items) {
            val kind = Meta.sendKind(p.first.mime.ifBlank { Meta.mimeFromName(p.first.name) }, p.first.size)
            if (kind == SendKind.DOCUMENT) {
                flushMedia(); docs += p; if (docs.size == 10) flushDocs()
            } else {
                flushDocs(); media += p; if (media.size == 10) flushMedia()
            }
        }
        flushMedia(); flushDocs(); return out
    }

    /** Já está na nuvem (hash, ou nome+tamanho se o hash ainda não estiver no índice). */
    fun alreadyOnCloud(hash: String, size: Long, name: String = ""): Boolean {
        if (Prefs.resendExisting) {
            val chat = Prefs.chatId
            if (hash.isNotBlank() && (Db.anyByHashInChat(chat, hash, size) != null || Db.anyByHashInChat(chat, hash) != null)) return true
            if (name.isNotBlank() && name != "item" && Db.anyByNameSizeInChat(chat, name, size) != null) return true
            return false
        }
        if (hash.isNotBlank() && (Db.anyByHash(hash, size) != null || Db.anyByHash(hash) != null)) return true
        if (name.isNotBlank() && name != "item" && Db.anyByNameSize(name, size) != null) return true
        return false
    }

    /** Envia um lote (álbum de até 10). Itens com erro de imagem saem da fila para o fim, sem contar como enviados. */
    suspend fun uploadGroup(c: Context, batch: List<Pair<LocalItem, String>>, onProgress: (Int, Float) -> Unit, chatId: Long = Prefs.chatId, topicId: Int = 0, folder: String = batch.first().first.bucket): UploadResult {
        val fresh = batch.filter { !alreadyOnCloud(it.second, it.first.size, it.first.name) }
        if (fresh.isEmpty()) return UploadResult()
        if (fresh.size <= 1) {
            val (i, h) = fresh.first()
            return uploadOrDefer(c, i, h, { onProgress(0, it) }, chatId, topicId, folder)
        }
        val thumbs = fresh.map { thumbFor(c, it.first) }
        try {
            val contents = fresh.mapIndexed { idx, (i, _) ->
                val kind = Meta.sendKind(i.mime.ifBlank { Meta.mimeFromName(i.name) }, i.size)
                val cap = if (idx == 0) captionFor(i, folder) else ""
                buildContent(kind, i.path, cap, thumbs[idx])
            }
            val metas = fresh.map { (i, h) -> metaOf(i, h) }
            var timedOut = false
            val started = try { startAlbum(contents, chatId, topicId) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: TdException) {
                if (e.code == 408) { timedOut = true; null } else null
            } catch (_: Exception) { null }
            if (started != null) {
                try {
                    awaitAlbum(started, onProgress, metas, chatId)
                    if (Prefs.originalPhotos) maybeUploadOriginals(c, fresh, chatId, topicId)
                    return UploadResult(sent = fresh)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    if (isCancel(e) || Prefs2.backupPaused) throw e
                    // álbum falhou (ex.: IMAGE_PROCESS_FAILED): envia um a um e adia só o que quebrar
                }
            } else if (timedOut) {
                val recovered = recoverAlbum(chatId, topicId, metas)
                if (recovered != null && recovered.size >= fresh.size) {
                    if (Prefs.originalPhotos) maybeUploadOriginals(c, fresh, chatId, topicId)
                    return UploadResult(sent = fresh)
                }
            }
            return uploadEachOrDefer(c, fresh, onProgress, chatId, topicId, folder)
        } finally { thumbs.forEach { it?.delete() } }
    }

    private suspend fun maybeUploadOriginals(c: Context, fresh: List<Pair<LocalItem, String>>, chatId: Long, topicId: Int) {
        val need = fresh.filter { (i, h) ->
            i.mime.startsWith("image/") && Db.originalByHash(chatId, h) == null && Db.anyByHash(h)?.folder != ORIGINALS_FOLDER
        }
        if (need.isNotEmpty()) try { uploadOriginalAlbum(c, need, { _, _ -> }, chatId, topicId) } catch (_: Exception) {}
    }

    private suspend fun uploadOrDefer(c: Context, i: LocalItem, h: String, onProgress: (Float) -> Unit, chatId: Long, topicId: Int, folder: String): UploadResult {
        if (alreadyOnCloud(h, i.size, i.name)) return UploadResult()
        return try {
            upload(c, i, h, onProgress, chatId, topicId, folder)
            UploadResult(sent = listOf(i to h))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            if (isCancel(e) || Prefs2.backupPaused) throw e
            UploadResult(deferred = listOf(DeferredUpload(i, h, e.message ?: "Falha no envio", folder, chatId, topicId)))
        }
    }

    private suspend fun uploadEachOrDefer(c: Context, fresh: List<Pair<LocalItem, String>>, onProgress: (Int, Float) -> Unit, chatId: Long, topicId: Int, folder: String): UploadResult {
        val sent = ArrayList<Pair<LocalItem, String>>()
        val deferred = ArrayList<DeferredUpload>()
        for ((idx, pair) in fresh.withIndex()) {
            val (i, h) = pair
            if (alreadyOnCloud(h, i.size, i.name)) { sent += pair; continue }
            val r = uploadOrDefer(c, i, h, { onProgress(idx, it) }, chatId, topicId, folder)
            sent += r.sent; deferred += r.deferred
        }
        return UploadResult(sent, deferred)
    }

    /** Segunda tentativa, no fim da fila: JPEG redimensionado e, se precisar, o original como documento. Não marca como enviado se falhar. */
    suspend fun retryDeferred(c: Context, list: List<DeferredUpload>, onProgress: (Int, Int, String) -> Unit): List<DeferredUpload> {
        val still = ArrayList<DeferredUpload>()
        list.forEachIndexed { idx, d ->
            onProgress(idx + 1, list.size, d.item.name)
            if (alreadyOnCloud(d.hash, d.item.size, d.item.name)) return@forEachIndexed
            val folder = d.folder
            var jpeg: File? = null
            val thumb = try { thumbFor(c, d.item) } catch (_: Exception) { null }
            try {
                if (d.item.mime.startsWith("image/") || Meta.mimeFromName(d.item.name).startsWith("image/")) {
                    jpeg = transcodeJpeg(d.item.path, c.cacheDir)
                    if (jpeg != null) {
                        try {
                            uploadFile(jpeg.absolutePath, jpeg.length(), captionFor(d.item, folder), thumb, { }, "image/jpeg", c.cacheDir, meta = metaOf(d.item, d.hash), chatId = d.chatId, topicId = d.topicId)
                            if (Prefs.originalPhotos) try { uploadOriginalAlbum(c, listOf(d.item to d.hash), { _, _ -> }, d.chatId, d.topicId) } catch (_: Exception) {}
                            return@forEachIndexed
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { if (isCancel(e) || Prefs2.backupPaused) throw e }
                    }
                }
                uploadFile(d.item.path, d.item.size, captionFor(d.item, folder), thumb, { }, d.item.mime, c.cacheDir, forceDocument = true, meta = metaOf(d.item, d.hash), chatId = d.chatId, topicId = d.topicId)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (isCancel(e) || Prefs2.backupPaused) throw e
                still += d.copy(error = e.message ?: d.error)
            } finally {
                jpeg?.delete(); thumb?.delete()
            }
        }
        return still
    }

    /** Converte uma foto (inclui HEIC) para JPEG com lado máximo 2560 px, só para o Telegram aceitar. O original não é alterado. */
    fun transcodeJpeg(src: String, dir: File, maxSide: Int = ImageProcess.JPEG_MAX_SIDE): File? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            val sample = (longest / maxSide).coerceAtLeast(1)
            val bmp = BitmapFactory.decodeFile(src, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val scaled = if (maxOf(bmp.width, bmp.height) > maxSide) {
                val s = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
                val w = (bmp.width * s).toInt().coerceAtLeast(1)
                val h = (bmp.height * s).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, w, h, true)
            } else bmp
            val out = File(dir, "nuvem_tx_${src.hashCode()}.jpg")
            FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            out.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) { null }
    }

    /** Depois do último item do tópico/álbum, marca o nome. */
    suspend fun sendAlbumMarker(album: String, chatId: Long = Prefs.chatId, topicId: Int = 0) {
        Td.send(TdApi.SendMessage().apply {
            this.chatId = chatId
            this.topicId = Topics.topic(topicId)
            inputMessageContent = TdApi.InputMessageText().apply { text = TdApi.FormattedText(Meta.albumTitle(album), emptyArray()) }
            options = sendOpts()
        })
    }

    suspend fun sendSummary(text: String, chatId: Long = Prefs.chatId) {
        val topic = Topics.generalId(chatId)
        Td.send(TdApi.SendMessage().apply {
            this.chatId = chatId
            this.topicId = Topics.topic(topic)
            inputMessageContent = TdApi.InputMessageText().apply { this.text = TdApi.FormattedText(text, emptyArray()) }
            options = sendOpts()
        }, 20_000)
    }

    /** Originais das fotos, em álbum de documentos (até 10), logo após o álbum de mídia. Sem legenda e sem resposta. */
    suspend fun uploadOriginalAlbum(c: Context, batch: List<Pair<LocalItem, String>>, onProgress: (Int, Float) -> Unit, chatId: Long, topicId: Int) {
        val need = batch.filter { Db.originalByHash(chatId, it.second) == null && Db.anyByHash(it.second)?.folder != ORIGINALS_FOLDER }
        if (need.isEmpty()) return
        for (chunk in need.chunked(10)) {
            val thumbs = chunk.map { thumbFor(c, it.first) }
            try {
                if (chunk.size == 1) {
                    val (i, h) = chunk.first()
                    uploadFile(i.path, i.size, "", thumbs[0], { onProgress(0, it) }, i.mime, c.cacheDir, forceDocument = true, meta = metaOf(i, h, original = true), chatId = chatId, topicId = topicId)
                    continue
                }
                val contents = chunk.mapIndexed { idx, (i, _) -> buildContent(SendKind.DOCUMENT, i.path, "", thumbs[idx]) }
                val metas = chunk.map { (i, h) -> metaOf(i, h, original = true) }
                val started = try { startAlbum(contents, chatId, topicId) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
                if (started == null) {
                    chunk.forEachIndexed { idx, (i, h) ->
                        uploadFile(i.path, i.size, "", thumbs[idx], { onProgress(idx, it) }, i.mime, c.cacheDir, forceDocument = true, meta = metaOf(i, h, original = true), chatId = chatId, topicId = topicId)
                    }
                } else awaitAlbum(started, onProgress, metas, chatId)
            } finally { thumbs.forEach { it?.delete() } }
        }
    }

    /** Mantido pra fotos que já estavam na nuvem sem o arquivo original. */
    suspend fun uploadOriginalIfNeeded(c: Context, i: LocalItem, hash: String, mediaMsg: TdApi.Message, onProgress: (Float) -> Unit) {
        if (!Prefs.originalPhotos) return
        if (mediaMsg.content !is TdApi.MessagePhoto) return
        val chat = mediaMsg.chatId
        if (Db.originalByHash(chat, hash) != null) return
        uploadOriginalAlbum(c, listOf(i to hash), { _, p -> onProgress(p) }, chat, (mediaMsg.topicId as? TdApi.MessageTopicForum)?.forumTopicId ?: 0)
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
        meta: Meta? = null,
        chatId: Long = Prefs.chatId,
        topicId: Int = 0,
    ): TdApi.Message {
        val resolved = mime.ifBlank { Meta.mimeFromName(path) }
        val kind = if (forceDocument) SendKind.DOCUMENT else Meta.sendKind(resolved, size)
        var generated: File? = null
        try {
            val useThumb = thumb ?: if (kind == SendKind.VIDEO) {
                generated = videoThumb(path, cacheDir ?: Td.cacheDir())
                generated
            } else null
            return try {
                sendContent(buildContent(kind, path, caption, useThumb), onProgress, replyTo, meta, chatId, topicId)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                // Foto/vídeo com erro (ex.: IMAGE_PROCESS_FAILED) não vai como documento aqui.
                // O backup adia o item e tenta JPEG + documento no fim da fila.
                throw e
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

    private suspend fun startAlbum(contents: List<TdApi.InputMessageContent>, chatId: Long = Prefs.chatId, topicId: Int = 0): Array<TdApi.Message> {
        val sent = Td.send(TdApi.SendMessageAlbum().apply {
            this.chatId = chatId; this.topicId = Topics.topic(topicId)
            inputMessageContents = contents.toTypedArray(); options = sendOpts()
        }, 90_000)
        val tmps = sent.messages
        if (tmps.isNullOrEmpty()) throw TdException(0, "Álbum vazio")
        return tmps
    }

    /** Depois de um timeout no SendMessageAlbum, tenta achar o lote já enviado (sem reenviar). */
    private suspend fun recoverAlbum(chatId: Long, topicId: Int, metas: List<Meta>): List<TdApi.Message>? {
        kotlinx.coroutines.delay(1_500)
        val r = try {
            if (topicId != 0) Td.send(TdApi.GetForumTopicHistory(chatId, topicId, 0, 0, 40), 20_000)
            else Td.send(TdApi.GetChatHistory().apply { this.chatId = chatId; fromMessageId = 0; offset = 0; limit = 40; onlyLocal = false }, 20_000)
        } catch (_: Exception) { return null }
        val cutoff = (System.currentTimeMillis() / 1000L - 180).toInt()
        val media = (r.messages ?: emptyArray()).filter { m ->
            m.date >= cutoff && remoteOf(m) != null && !isIndexMessage(m)
        }.sortedBy { it.id }
        if (media.size < metas.size) return null
        val slice = media.takeLast(metas.size)
        slice.zip(metas).forEach { (msg, meta) -> remember(msg, meta) }
        return slice
    }

    private suspend fun awaitAlbum(tmps: Array<TdApi.Message>, onProgress: (Int, Float) -> Unit, metas: List<Meta>? = null, chatId: Long = Prefs.chatId): List<TdApi.Message> {
        val chat = chatId
        val out = ArrayList<TdApi.Message>(tmps.size)
        try {
            for (tmp in tmps) {
                val fileId = fileOf(tmp)?.id ?: -1
                val res = try { awaitSendOrStall(tmp.id, fileId) { p -> onProgress(out.size, p) } }
                catch (e: kotlinx.coroutines.CancellationException) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        tmps.forEach { cancelSending(chat, it.id, fileOf(it)?.id ?: -1) }
                    }
                    throw e
                }
                catch (e: TdException) {
                    if (e.code == 408) withContext(kotlinx.coroutines.NonCancellable) {
                        tmps.drop(out.size).forEach { cancelSending(chat, it.id, fileOf(it)?.id ?: -1) }
                    }
                    throw e
                }
                val msg = res.message ?: throw TdException(res.error?.code ?: 0, res.error?.message ?: "Falha no envio do álbum")
                val r = remoteOf(msg) ?: throw TdException(0, "Mensagem sem mídia")
                if (!r.file.remote.isUploadingCompleted) throw TdException(0, "Upload incompleto")
                val meta = metas?.getOrNull(out.size)
                if (meta != null) remember(msg, meta) else toCloud(msg)?.let { Db.putCloud(it); Db.bump() }
                out += msg
            }
            return out
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    }

    private suspend fun sendContent(content: TdApi.InputMessageContent, onProgress: (Float) -> Unit, replyTo: Long = 0L, meta: Meta? = null, chatId: Long = Prefs.chatId, topicId: Int = 0): TdApi.Message {
        val chat = chatId
        var last: Exception? = null
        for (attempt in 0 until StallPolicy.MAX_TRIES) {
            val tmp = Td.send(TdApi.SendMessage().apply {
                this.chatId = chat; this.topicId = Topics.topic(topicId)
                inputMessageContent = content; options = sendOpts()
                if (replyTo != 0L) this.replyTo = TdApi.InputMessageReplyToMessage().apply { messageId = replyTo }
            }, 90_000)
            val fileId = fileOf(tmp)?.id ?: -1
            try {
                val res = try { awaitSendOrStall(tmp.id, fileId, onProgress) }
                catch (e: kotlinx.coroutines.CancellationException) {
                    withContext(kotlinx.coroutines.NonCancellable) { cancelSending(chat, tmp.id, fileId) }
                    throw e
                } catch (e: TdException) {
                    if (e.code == 408) withContext(kotlinx.coroutines.NonCancellable) { cancelSending(chat, tmp.id, fileId) }
                    throw e
                }
                val msg = res.message ?: throw TdException(res.error?.code ?: 0, res.error?.message ?: "Falha no envio")
                val r = remoteOf(msg) ?: throw TdException(0, "Mensagem sem mídia")
                if (!r.file.remote.isUploadingCompleted) throw TdException(0, "Upload incompleto")
                if (meta != null) remember(msg, meta) else toCloud(msg)?.let { Db.putCloud(it); Db.bump() }
                return msg
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (isCancel(e) || Prefs2.backupPaused) throw e
                last = e
                if (!StallPolicy.shouldRetry(attempt)) break
                delay(StallPolicy.backoffMs(attempt))
            }
        }
        throw last ?: TdException(0, "Falha no envio")
    }

    /** Espera o envio, mas aborta se o upload ficar parado sem progresso. */
    private suspend fun awaitSendOrStall(tmpId: Long, fileId: Int, onProgress: (Float) -> Unit): br.geracaoai.nuvem.tg.SendResult = coroutineScope {
        var lastAt = System.currentTimeMillis()
        var lastBytes = -1L
        val send = async { Td.awaitSend(tmpId) }
        val watch = launch {
            if (fileId > 0) launch {
                Td.fileUpdates.collect { f ->
                    if (f.id == fileId) {
                        val up = f.remote.uploadedSize.toLong()
                        if (up != lastBytes) { lastBytes = up; lastAt = System.currentTimeMillis() }
                        if (f.expectedSize > 0) onProgress(f.remote.uploadedSize.toFloat() / f.expectedSize)
                    }
                }
            }
            while (isActive && send.isActive) {
                delay(5_000)
                if (StallPolicy.stalled(lastAt, System.currentTimeMillis())) {
                    send.cancel()
                    break
                }
            }
        }
        try {
            send.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (Prefs2.backupPaused) throw e
            if (StallPolicy.stalled(lastAt, System.currentTimeMillis())) {
                throw TdException(408, "Envio parado (sem progresso). Tentando de novo.")
            }
            throw e
        } finally { watch.cancel() }
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
        if (Prefs.destChats().isEmpty()) return Freeable(emptyList(), 0)
        val items = LocalMedia.all(c).filter { i ->
            val h = Db.cachedHash(i.path, i.size, i.mtime) ?: return@filter false
            Db.anyByHash(h, i.size) != null
        }
        return Freeable(items, items.sumOf { it.size })
    }

    fun freeableOlderThan(c: Context, days: Int): Freeable {
        if (days <= 0) return Freeable(emptyList(), 0)
        val cutoff = System.currentTimeMillis() - days * 86400000L
        val all = freeableQuick(c)
        val items = all.items.filter { it.taken in 1 until cutoff || (it.taken <= 0 && it.mtime in 1 until cutoff) }
        return Freeable(items, items.sumOf { it.size })
    }

    /** Verificação rigorosa antes de apagar: confere na hora se a mensagem ainda existe no Telegram. */
    suspend fun verifyForDelete(items: List<LocalItem>, onProgress: (Int) -> Unit): List<LocalItem> = withContext(Dispatchers.IO) {
        items.filterIndexed { idx, i ->
            onProgress(idx)
            try {
                val f = File(i.path); if (!f.exists() || f.length() != i.size) return@filterIndexed false
                val h = Db.cachedHash(i.path, i.size, i.mtime) ?: Hash.quick(f)
                val ci = Db.anyByHash(h, i.size) ?: Db.anyByHash(h) ?: Db.anyByNameSize(i.name, i.size) ?: return@filterIndexed false
                val m = try {
                    Td.send(TdApi.GetMessage(ci.chatId, ci.msgId), 20_000)
                } catch (e: TdException) {
                    if (CloudSync.isMessageGone(e.code, e.message) || CloudSync.isChatGone(e.code, e.message)) {
                        Db.deleteCloud(ci.chatId, ci.msgId)
                        val orig = if (ci.hash.isNotBlank()) Db.originalByHash(ci.chatId, ci.hash) else null
                        if (orig != null) Db.deleteCloud(orig.chatId, orig.msgId)
                    }
                    return@filterIndexed false
                }
                val r = remoteOf(m) ?: return@filterIndexed false
                val meta = Meta.parse(captionOf(m))
                val match = when {
                    meta != null -> (meta.hash == h || meta.hash.isBlank()) && (meta.size == i.size || meta.size == 0L)
                    ci.size == i.size -> true
                    else -> false
                }
                match && r.file.remote.isUploadingCompleted && (r.file.remote.id.isNotEmpty() || r.file.remote.uniqueId.isNotEmpty())
            } catch (_: Exception) { false }
        }
    }

    /**
     * Confere no Telegram quais mensagens do índice ainda existem.
     * O que foi apagado (ou histórico limpo) sai do banco local: some da galeria,
     * o "Liberar espaço" não conta, e o próximo backup reenvia se ainda estiver no celular.
     */
    suspend fun reconcileCloud(force: Boolean = false, onProgress: ((Int, Int) -> Unit)? = null): Int = withContext(Dispatchers.IO) {
        reconcileLock.withLock {
            if (!force && lastReconcileAt != 0L && System.currentTimeMillis() - lastReconcileAt < 8_000L) return@withLock 0
            if (Prefs.allKnownChats().isEmpty()) { lastReconcileAt = System.currentTimeMillis(); return@withLock 0 }
            var removed = 0
            for (chat in Prefs.allKnownChats()) {
                val stored = Db.idsForChat(chat)
                if (stored.isEmpty()) continue
                val found = HashSet<Long>()
                var goneChat = false
                for (chunk in stored.chunked(100)) {
                    var tries = 0
                    while (tries < 5) {
                        try {
                            val r = Td.send(TdApi.GetMessages(chat, chunk.toLongArray()), 20_000)
                            found += CloudSync.foundIds((r.messages ?: emptyArray()).map { it?.id })
                            onProgress?.invoke(found.size + removed, stored.size)
                            break
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: TdException) {
                            if (CloudSync.isChatGone(e.code, e.message)) { goneChat = true; break }
                            val sec = floodWaitSeconds(e.message) ?: if (e.code == 429) 5 else null
                            if (sec != null) { delay((sec + 1) * 1000L); tries++ }
                            else {
                                // fallback: um a um neste lote
                                for (id in chunk) {
                                    try {
                                        val m = Td.send(TdApi.GetMessage(chat, id), 12_000)
                                        if (m.id != 0L) found += m.id
                                    } catch (e2: TdException) {
                                        if (CloudSync.isChatGone(e2.code, e2.message)) { goneChat = true; break }
                                        // missing: simply not added to found
                                    } catch (_: Exception) {}
                                }
                                break
                            }
                        }
                    }
                    if (goneChat) break
                }
                if (goneChat) {
                    removed += stored.size
                    Db.clearCloud(chat)
                } else {
                    val missing = CloudSync.missingIds(stored, found)
                    if (missing.isNotEmpty()) {
                        Db.forgetMessages(chat, missing)
                        removed += missing.size
                    }
                }
            }
            lastReconcileAt = System.currentTimeMillis()
            if (removed > 0) Db.bump()
            removed
        }
    }

    // ---------- Arquivos da nuvem ----------
    suspend fun message(ci: CloudItem): TdApi.Message = Td.send(TdApi.GetMessage(ci.chatId, ci.msgId))

    /**
     * Resolve o arquivo atual no TDLib (o file id muda a cada sessão).
     * Usa GetMessage, e se falhar tenta o remote id / uniqueId.
     * Já dispara o download do começo, com prioridade alta, pra o player começar rápido.
     */
    suspend fun playable(ci: CloudItem): Playable = withContext(Dispatchers.IO) {
        val orig = originalItem(ci)?.takeIf {
            it.mime.startsWith("video/") || it.mime.contains("mp4") || it.mime.contains("quicktime")
        }
        val candidates = listOfNotNull(orig, ci).distinctBy { it.msgId to it.chatId }
        var lastErr: Exception? = null
        var file: TdApi.File? = null
        var mime = ci.mime.ifBlank { "video/mp4" }
        var name = ci.name
        var unique = ci.uniqueId
        var remoteId = ""
        for (src in candidates) {
            try {
                val msg = message(src)
                val rf = remoteOf(msg) ?: continue
                file = rf.file
                mime = rf.mime.ifBlank { mime }
                name = rf.name.ifBlank { name }
                unique = rf.uniqueId.ifBlank { unique }
                remoteId = rf.file.remote?.id ?: remoteId
                if (file != null && file.id > 0) break
            } catch (e: Exception) { lastErr = e }
        }
        if (file == null || file.id <= 0) {
            val ids = listOf(remoteId, unique, ci.uniqueId).filter { it.isNotBlank() }.distinct()
            val types = listOf(TdApi.FileTypeVideo(), TdApi.FileTypeDocument(), TdApi.FileTypeAnimation(), TdApi.FileTypeNone())
            loop@ for (id in ids) for (tp in types) {
                try {
                    val f = Td.send(TdApi.GetRemoteFile(id, tp), 15_000)
                    if (f != null && f.id > 0) { file = f; break@loop }
                } catch (e: Exception) { lastErr = e }
            }
        }
        val opened = file ?: throw TdException((lastErr as? TdException)?.code ?: 404,
            lastErr?.message ?: "Não achei este vídeo no Telegram. Ele pode ter sido apagado.")
        val fresh = try { Td.send(TdApi.GetFile(opened.id), 15_000) } catch (_: Exception) { opened }
        if (fresh.id <= 0) throw TdException(400, "O Telegram não reconheceu o arquivo deste vídeo. Tente de novo.")
        br.geracaoai.nuvem.tg.TgStream.update(fresh)
        val size = PlaybackFile.resolvedSize(fresh.size, fresh.expectedSize, ci.size)
        try {
            val started = Td.send(TdApi.DownloadFile(fresh.id, 32, 0, 0, false), 20_000)
            br.geracaoai.nuvem.tg.TgStream.update(started)
            val loc = started.local
            val path = loc?.path ?: fresh.local?.path ?: ""
            val done = loc?.isDownloadingCompleted == true
            return@withContext Playable(started.id, PlaybackFile.resolvedSize(started.size, started.expectedSize, size),
                mime, name, started.remote?.uniqueId ?: unique, started.remote?.id ?: remoteId, path, done)
        } catch (e: TdException) {
            // file id inválido nesta sessão: última tentativa pelo remote id
            if ("FILE_ID" in (e.message ?: "").uppercase() || e.code == 400) {
                val rid = fresh.remote?.id?.ifBlank { remoteId } ?: remoteId
                if (rid.isNotBlank()) {
                    val f2 = Td.send(TdApi.GetRemoteFile(rid, TdApi.FileTypeVideo()), 15_000)
                    Td.send(TdApi.DownloadFile(f2.id, 32, 0, 0, false), 20_000)
                    br.geracaoai.nuvem.tg.TgStream.update(f2)
                    val sz = PlaybackFile.resolvedSize(f2.size, f2.expectedSize, size)
                    return@withContext Playable(f2.id, sz, mime, name, f2.remote?.uniqueId ?: unique, f2.remote?.id ?: rid, f2.local?.path ?: "", f2.local?.isDownloadingCompleted == true)
                }
            }
            throw TdException(e.code, "Não deu pra começar o download: ${e.message ?: "erro"}")
        }
    }

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

    fun restoreRelativePath(ci: CloudItem): String {
        val fromMeta = ci.path.substringBeforeLast('/').trim('/').ifBlank { "" }
        if (fromMeta.isNotBlank() && !fromMeta.startsWith("/")) return fromMeta + "/"
        val base = if (ci.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        return "$base/Nuvem/${ci.folder.ifBlank { "Outros" }}/"
    }

    /** Restaura para a pasta original, se soubermos; senão Pictures/Movies/Nuvem/álbum. */
    suspend fun restore(c: Context, ci: CloudItem): Boolean = withContext(Dispatchers.IO) {
        val src = fullPath(ci) ?: return@withContext false
        val video = ci.isVideo
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ci.name); put(MediaStore.MediaColumns.MIME_TYPE, ci.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, restoreRelativePath(ci))
            put(MediaStore.MediaColumns.DATE_TAKEN, ci.taken); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var uri: android.net.Uri? = null
        var pending = false
        try {
            uri = c.contentResolver.insert(coll, cv) ?: return@withContext false
            pending = true
            c.contentResolver.openOutputStream(uri)!!.use { o -> File(src).inputStream().use { it.copyTo(o) } }
            c.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            pending = false
            true
        } catch (_: Exception) {
            if (pending && uri != null) {
                try { c.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) } catch (_: Exception) {}
                try { c.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            }
            false
        }
    }

    suspend fun restoreAlbum(c: Context, folder: String, onProgress: ((Int, Int) -> Unit)? = null): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val items = Db.allCloudMany(Prefs.galleryChats() + listOf(Prefs.secretChatId).filter { it != 0L }).filter { it.folder == folder }
        var ok = 0
        items.forEachIndexed { i, ci ->
            onProgress?.invoke(i, items.size)
            if (try { restore(c, ci) } catch (_: Exception) { false }) ok++
        }
        ok to items.size
    }

    // ---------- Índice invisível de metadados ----------
    suspend fun findIndexMessage(chat: Long): TdApi.Message? {
        val known = Prefs.indexId(chat)
        if (known != 0L) {
            try {
                val m = Td.send(TdApi.GetMessage(chat, known), 15_000)
                if (isIndexMessage(m)) return m
            } catch (_: Exception) {}
        }
        try {
            val found = Td.send(TdApi.SearchChatMessages().apply {
                chatId = chat
                query = Meta.INDEX_FILE
                fromMessageId = 0
                offset = 0
                limit = 20
                filter = TdApi.SearchMessagesFilterDocument()
            }, 20_000)
            found.messages?.firstOrNull { isIndexMessage(it) }?.let { return it }
        } catch (_: Exception) {}
        return null
    }

    suspend fun pullIndex(chat: Long) {
        val msg = findIndexMessage(chat) ?: return
        Prefs.setIndexId(chat, msg.id)
        val f = fileOf(msg) ?: return
        val path = Td.send(TdApi.DownloadFile(f.id, 32, 0, 0, true), 60_000).local.path.ifBlank { return }
        val recs = Meta.unpackIndex(File(path).readText())
        if (recs.isEmpty()) return
        for (r in recs) {
            val folder = when {
                r.meta.original -> ORIGINALS_FOLDER
                r.meta.folder.isNotBlank() -> r.meta.folder
                else -> "Outros"
            }
            val existing = Db.getCloud(chat, r.msgId)
            Db.putCloud(CloudItem(r.msgId, chat, r.meta.name, r.meta.mime, r.meta.size, r.meta.taken, folder, r.meta.path, r.meta.hash, existing?.mini))
        }
        Db.bump()
    }

    suspend fun pushIndex(chat: Long = Prefs.chatId) {
        if (chat == 0L) return
        val items = Db.allForIndex(chat)
        val dir = File(Td.filesDir(), "index").apply { mkdirs() }
        val f = File(dir, Meta.INDEX_FILE)
        f.writeText(Meta.packIndex(items))
        val topic = Topics.generalId(chat)
        val content = TdApi.InputMessageDocument().apply {
            document = TdApi.InputDocument().apply {
                document = TdApi.InputFileLocal(f.absolutePath)
                disableContentTypeDetection = true
            }
            caption = TdApi.FormattedText("", emptyArray())
        }
        val msg = try {
            Td.send(TdApi.SendMessage().apply {
                chatId = chat
                topicId = Topics.topic(topic)
                inputMessageContent = content
                options = sendOpts()
            }, 60_000)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            f.delete(); throw e
        }
        val sent = try { Td.awaitSend(msg.id) } catch (e: Exception) { f.delete(); throw e }
        val done = sent.message ?: run { f.delete(); return }
        try { Td.send(TdApi.PinChatMessage(chat, done.id, true, false), 15_000) } catch (_: Exception) {}
        val old = Prefs.indexId(chat)
        Prefs.setIndexId(chat, done.id)
        if (old != 0L && old != done.id) {
            try { Td.send(TdApi.DeleteMessages(chat, longArrayOf(old), true), 15_000) } catch (_: Exception) {}
        }
        f.delete()
    }

    /**
     * Remove legendas antigas "#nuvem v1" (edita no Telegram) e deixa só a hashtag curta.
     * Salva os metadados no banco e atualiza o índice antes de apagar o texto enorme.
     */
    suspend fun clearOldCaptions(onProgress: ((Int, Int) -> Unit)? = null): Int = withContext(Dispatchers.IO) {
        ensureDestination()
        var cleaned = 0; var scanned = 0
        for (chat in Prefs.destChats()) {
            try { pullIndex(chat) } catch (_: Exception) {}
            val topics = try { Topics.list(chat) } catch (_: Exception) { emptyList() }
            val topicIds = if (topics.isEmpty()) listOf(0) else topics.map { it.forumTopicId }
            for (topicId in topicIds) {
                var from = 0L; var emptyRounds = 0; var pages = 0
                while (true) {
                    val r = try {
                        if (topicId != 0) Td.send(TdApi.GetForumTopicHistory(chat, topicId, from, 0, 100), 20_000)
                        else Td.send(TdApi.GetChatHistory().apply { chatId = chat; fromMessageId = from; offset = 0; limit = 100; onlyLocal = false }, 20_000)
                    } catch (e: TdException) {
                        if (e.code == 408 && from == 0L && pages == 0) break
                        throw e
                    }
                    val msgs = r.messages ?: emptyArray()
                    for (m in msgs) {
                        scanned++
                        val cap = captionOf(m) ?: continue
                        if (!Meta.hasLegacyCaption(cap)) continue
                        val item = toCloud(m)
                        item?.let { Db.putCloud(it); Db.bump() }
                        val capNew = if (item != null) Classify.shortCaption(item.taken, item.folder, item.folder == ORIGINALS_FOLDER) else ""
                        try {
                            Td.send(TdApi.EditMessageCaption().apply {
                                chatId = chat; messageId = m.id
                                caption = TdApi.FormattedText(capNew, emptyArray())
                            }, 20_000)
                            cleaned++
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (e: TdException) {
                            if (e.code == 429) {
                                kotlinx.coroutines.delay(3_000)
                                try {
                                    Td.send(TdApi.EditMessageCaption().apply {
                                        chatId = chat; messageId = m.id
                                        caption = TdApi.FormattedText(capNew, emptyArray())
                                    }, 20_000)
                                    cleaned++
                                } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                        onProgress?.invoke(cleaned, scanned)
                    }
                    val s = ChatHistoryWalk.next(from, emptyRounds, msgs.map { it.id }.toLongArray(), true, 0, pages)
                    from = s.nextFromId; emptyRounds = s.emptyRounds; pages = s.pages
                    if (s.done) break
                }
            }
            try { pushIndex(chat) } catch (_: Exception) {}
        }
        cleaned
    }


    fun toDupItem(m: TdApi.Message): DupItem? {
        val ci = toCloud(m) ?: return null
        val r = remoteOf(m)
        return DupItem(
            m.id, m.chatId, ci.name, ci.mime, ci.size, ci.taken, m.date, ci.folder, ci.hash,
            r?.uniqueId?.ifBlank { ci.uniqueId } ?: ci.uniqueId,
            ci.folder == ORIGINALS_FOLDER || (Meta.parse(captionOf(m))?.original == true),
        )
    }

    suspend fun collectDupItems(onProgress: ((Int) -> Unit)? = null): List<DupItem> = withContext(Dispatchers.IO) {
        try { syncIndex { onProgress?.invoke(it) } } catch (_: Exception) {}
        val out = ArrayList<DupItem>()
        val chats = Prefs.allKnownChats()
        for (chat in chats) {
            val topics = try { Topics.list(chat) } catch (_: Exception) { emptyList() }
            val topicIds = if (topics.isEmpty()) listOf(0) else topics.map { it.forumTopicId }
            for (topicId in topicIds) {
                var from = 0L; var emptyRounds = 0; var pages = 0
                while (true) {
                    val r = try {
                        if (topicId != 0) Td.send(TdApi.GetForumTopicHistory(chat, topicId, from, 0, 100), 20_000)
                        else Td.send(TdApi.GetChatHistory().apply { chatId = chat; fromMessageId = from; offset = 0; limit = 100; onlyLocal = false }, 20_000)
                    } catch (e: TdException) {
                        if (e.code == 408 && from == 0L && pages == 0) break else throw e
                    }
                    val msgs = r.messages ?: emptyArray()
                    for (m in msgs) toDupItem(m)?.let { out += it }
                    onProgress?.invoke(out.size)
                    val s = ChatHistoryWalk.next(from, emptyRounds, msgs.map { it.id }.toLongArray(), true, 0, pages)
                    from = s.nextFromId; emptyRounds = s.emptyRounds; pages = s.pages
                    if (s.done) break
                }
            }
        }
        out
    }

    suspend fun scanDuplicates(onProgress: ((Int) -> Unit)? = null): DupPlan =
        Dedup.plan(collectDupItems(onProgress))

    suspend fun deleteDuplicates(plan: DupPlan, onProgress: ((Int, Int) -> Unit)? = null): Int = withContext(Dispatchers.IO) {
        val byChat = plan.delete.groupBy { it.chatId }
        var done = 0
        val total = plan.delete.size
        for ((chat, items) in byChat) {
            for (chunk in items.map { it.msgId }.distinct().chunked(100)) {
                var pending = chunk
                var tries = 0
                while (pending.isNotEmpty() && tries < 5) {
                    try {
                        Td.send(TdApi.DeleteMessages(chat, pending.toLongArray(), true), 30_000)
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: TdException) {
                        val sec = floodWaitSeconds(e.message) ?: if (e.code == 429) 5 else null
                        if (sec != null) {
                            kotlinx.coroutines.delay((sec + 1) * 1000L)
                            tries++
                        } else throw e
                    }
                }
                for (id in chunk) try { Db.deleteCloud(chat, id) } catch (_: Exception) {}
                done += chunk.size
                onProgress?.invoke(done, total)
            }
        }
        for (chat in byChat.keys) try { pushIndex(chat) } catch (_: Exception) {}
        Db.bump()
        done
    }

}
