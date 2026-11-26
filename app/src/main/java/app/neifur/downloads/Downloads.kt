package app.neifur.downloads

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import app.neifur.AnimeStub
import app.neifur.core.ApiError
import app.neifur.core.AppJson
import app.neifur.core.userMessage
import app.neifur.data.Prefs
import app.neifur.data.a365.Anime365
import app.neifur.data.a365.Episode
import app.neifur.data.a365.Picker
import app.neifur.data.a365.Translation
import app.neifur.data.a365.TranslationKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One episode saved on this phone for offline playback (MP4 + optional ASS subtitles). */
@Serializable
data class DownloadedEpisode(
    val episodeId: Long,
    val animeId: String,
    val animeTitle: String,
    val animePoster: String? = null,
    val episodeNumber: Int,
    val episodeLabel: String,
    val translationId: Long,
    val translationKind: String,
    val studioKey: String,
    val studioName: String,
    val subLang: String = "ru",
    val height: Int,
    val videoFile: String,
    val subFile: String? = null,
    val sizeBytes: Long,
    val downloadedAt: Long,
) {
    fun toEpisode(): Episode = Episode(
        id = episodeId,
        episodeFull = episodeLabel,
        episodeInt = episodeNumber.toDouble(),
        seriesId = animeId.toLongOrNull() ?: 0L,
    )

    fun toTranslation(): Translation {
        val kind = runCatching { TranslationKind.valueOf(translationKind) }.getOrDefault(TranslationKind.VoiceRu)
        val (typeStr, typeKind, typeLang) = when (kind) {
            TranslationKind.VoiceRu -> Triple("voiceRu", "voice", "ru")
            TranslationKind.SubRu -> Triple("subRu", "sub", "ru")
            TranslationKind.VoiceEn -> Triple("voiceEn", "voice", "en")
            TranslationKind.SubEn -> Triple("subEn", "sub", "en")
            TranslationKind.VoiceUk -> Triple("voiceUk", "voice", "uk")
            TranslationKind.SubUk -> Triple("subUk", "sub", "uk")
            TranslationKind.SubJa -> Triple("subJa", "sub", "ja")
            TranslationKind.Raw -> Triple("raw", "raw", "ja")
            TranslationKind.Hidden -> Triple("voiceRu", "voice", "ru")
        }
        return Translation(
            id = translationId,
            authorsSummary = studioName,
            type = typeStr,
            typeKind = typeKind,
            typeLang = typeLang,
            episodeId = episodeId,
            height = height,
        )
    }

    fun stub(): AnimeStub = AnimeStub(animeId, animeTitle, animePoster)
}

/** One anime's downloaded episodes grouped together for the Downloads screen and offline Home. */
data class DownloadedAnime(
    val animeId: String,
    val title: String,
    val poster: String?,
    val episodes: List<DownloadedEpisode>,
) {
    val totalBytes: Long get() = episodes.sumOf { it.sizeBytes }
    val latestAt: Long get() = episodes.maxOfOrNull { it.downloadedAt } ?: 0L
    fun stub(): AnimeStub = AnimeStub(animeId, title, poster)
}

/** Live state of an in-flight or failed download. */
sealed interface DownloadStatus {
    val episodeId: Long
    val animeId: String
    val animeTitle: String
    val animePoster: String?
    val episodeNumber: Int
    val episodeLabel: String

    data class Queued(
        override val episodeId: Long,
        override val animeId: String,
        override val animeTitle: String,
        override val animePoster: String?,
        override val episodeNumber: Int,
        override val episodeLabel: String,
    ) : DownloadStatus

    data class Running(
        override val episodeId: Long,
        override val animeId: String,
        override val animeTitle: String,
        override val animePoster: String?,
        override val episodeNumber: Int,
        override val episodeLabel: String,
        val progress: Float,
        val bytes: Long,
        val totalBytes: Long,
    ) : DownloadStatus

    data class WaitingWifi(
        override val episodeId: Long,
        override val animeId: String,
        override val animeTitle: String,
        override val animePoster: String?,
        override val episodeNumber: Int,
        override val episodeLabel: String,
    ) : DownloadStatus

    data class Failed(
        override val episodeId: Long,
        override val animeId: String,
        override val animeTitle: String,
        override val animePoster: String?,
        override val episodeNumber: Int,
        override val episodeLabel: String,
        val message: String,
    ) : DownloadStatus
}

data class DownloadsState(
    val items: List<DownloadedEpisode> = emptyList(),
    val active: Map<Long, DownloadStatus> = emptyMap(),
) {
    val totalBytes: Long get() = items.sumOf { it.sizeBytes }
    val runningCount: Int get() = active.values.count { it is DownloadStatus.Queued || it is DownloadStatus.Running }
    val currentRunning: DownloadStatus.Running? get() = active.values.filterIsInstance<DownloadStatus.Running>().firstOrNull()

    fun find(episodeId: Long): DownloadedEpisode? = items.firstOrNull { it.episodeId == episodeId }
    fun isDownloaded(episodeId: Long): Boolean = items.any { it.episodeId == episodeId }

    fun byAnime(): List<DownloadedAnime> = items
        .groupBy { it.animeId }
        .map { (id, list) ->
            val newest = list.maxBy { it.downloadedAt }
            DownloadedAnime(id, newest.animeTitle, newest.animePoster, list.sortedBy { it.episodeNumber })
        }
        .sortedByDescending { it.latestAt }
}

/** Resolved local video and optional subtitle file ready for libmpv. */
data class LocalEpisode(val meta: DownloadedEpisode, val video: File, val sub: File?)

/** Human-readable size in Russian ("148 МБ", "1,4 ГБ", "< 1 МБ"). */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 МБ"
    bytes < 1L shl 20 -> "< 1 МБ"
    bytes < 1L shl 30 -> "${bytes / (1L shl 20)} МБ"
    else -> String.format(Locale.forLanguageTag("ru"), "%.1f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
}

/** Parses total entity length from an HTTP `Content-Range` header (e.g. `"bytes 100-199/500"` → `500L`). */
internal fun parseContentRangeTotal(header: String?): Long? {
    val totalStr = header?.substringAfter('/', "")?.trim()?.takeIf { it.isNotEmpty() && it != "*" } ?: return null
    return totalStr.toLongOrNull()?.takeIf { it > 0L }
}

private data class PendingDownload(
    val anime: AnimeStub,
    val episode: Episode,
    val pickKey: String?,
    val pickName: String?,
    val token: Long,
)

/**
 * Offline episode downloader and local library. Stores files in app-private external storage with `.nomedia`
 * so downloaded anime never pollutes the system gallery, and drives [DownloadService] while work is active.
 */
class DownloadManager(
    private val app: Application,
    private val a365: Anime365,
    private val prefs: Prefs,
    baseHttp: OkHttpClient,
    private val scope: CoroutineScope,
    private val networkKey: StateFlow<String> = MutableStateFlow(""),
) {
    private val dir: File = (app.getExternalFilesDir("downloads") ?: File(app.filesDir, "downloads")).apply {
        mkdirs()
        runCatching { File(this, ".nomedia").createNewFile() }
    }
    private val indexFile = File(dir, "index.json")
    private val listSer = ListSerializer(DownloadedEpisode.serializer())
    private val indexMutex = Mutex()
    private val tokenGen = AtomicLong(0)
    private val activeTokens = ConcurrentHashMap<Long, Long>()

    // Dedicated client with generous read timeout and no total call timeout for multi-hundred-MB MP4 streams.
    private val http: OkHttpClient = baseHttp.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(DownloadsState())
    val state: StateFlow<DownloadsState> = _state

    private val queue = ArrayDeque<PendingDownload>()
    private val waitingWifi = LinkedHashMap<Long, PendingDownload>()
    private val requests = HashMap<Long, PendingDownload>()
    @Volatile private var currentId: Long? = null
    @Volatile private var pauseForWifiId: Long? = null
    @Volatile private var wifiOnlyCached: Boolean = false
    @Volatile private var activeCall: Call? = null
    private var currentJob: Job? = null
    private var pumpJob: Job? = null

    private fun isOnWifi(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            !cm.isActiveNetworkMetered
    }

    private fun waitingStatus(req: PendingDownload) = DownloadStatus.WaitingWifi(
        req.episode.id, req.anime.id, req.anime.title, req.anime.poster, req.episode.number, req.episode.label,
    )

    private fun queuedStatus(req: PendingDownload) = DownloadStatus.Queued(
        req.episode.id, req.anime.id, req.anime.title, req.anime.poster, req.episode.number, req.episode.label,
    )

    init {
        scope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val raw = readIndex()
                val loaded = raw.filter { File(dir, it.videoFile).exists() }
                val activePart = currentId?.let { "ep_$it.mp4.part" }
                // Clean leftover temp files from an interrupted process kill without touching active downloads.
                dir.listFiles()?.filter { it.name.endsWith(".part") && it.name != activePart }
                    ?.forEach { runCatching { it.delete() } }
                if (loaded.size != raw.size) writeIndex(loaded)
                val existing = _state.value.items
                val merged = if (existing.isEmpty()) loaded else (existing + loaded).distinctBy { it.episodeId }
                _state.update { it.copy(items = merged) }
            }
        }
        // Automatically pause active/queued downloads on cellular when Wi-Fi-only is on, and resume on Wi-Fi.
        scope.launch {
            combine(networkKey, prefs.wifiOnlyDownloads) { _, wifiOnly -> wifiOnly }.collect { wifiOnly ->
                wifiOnlyCached = wifiOnly
                if (wifiOnly && !isOnWifi()) {
                    val moved = synchronized(queue) {
                        val list = queue.toList()
                        queue.clear()
                        list.forEach { waitingWifi[it.episode.id] = it }
                        list
                    }
                    if (moved.isNotEmpty()) {
                        _state.update { st ->
                            st.copy(active = st.active + moved.associate { it.episode.id to waitingStatus(it) })
                        }
                    }
                    val activeId = currentId
                    if (activeId != null) {
                        pauseForWifiId = activeId
                        activeCall?.cancel()
                    }
                } else {
                    val resume = synchronized(queue) {
                        val list = waitingWifi.values.toList()
                        waitingWifi.clear()
                        list.forEach { req ->
                            queue.removeAll { it.episode.id == req.episode.id }
                            queue.addLast(req)
                        }
                        list
                    }
                    if (resume.isNotEmpty()) {
                        _state.update { st ->
                            st.copy(active = st.active + resume.associate { it.episode.id to queuedStatus(it) })
                        }
                        startService()
                        pump()
                    }
                }
            }
        }
    }

    /** Returns the verified local file pair if [episodeId] is saved on disk, or null otherwise. */
    fun localFile(episodeId: Long): LocalEpisode? {
        val meta = _state.value.find(episodeId) ?: return null
        val video = File(dir, meta.videoFile).takeIf { it.exists() && it.length() > 0L } ?: return null
        val sub = meta.subFile?.let { File(dir, it) }?.takeIf { it.exists() && it.length() > 0L }
        return LocalEpisode(meta, video, sub)
    }

    /** Queues [episode] for download using the chosen studio (or preferred defaults) and current download quality. */
    fun enqueue(anime: AnimeStub, episode: Episode, pickKey: String?, pickName: String?) {
        val s = _state.value
        if (s.isDownloaded(episode.id)) return
        val existing = s.active[episode.id]
        if (existing is DownloadStatus.Queued || existing is DownloadStatus.Running || existing is DownloadStatus.WaitingWifi) return

        val token = tokenGen.incrementAndGet()
        activeTokens[episode.id] = token
        val req = PendingDownload(anime, episode, pickKey, pickName, token)
        if (wifiOnlyCached && !isOnWifi()) {
            synchronized(queue) {
                requests[episode.id] = req
                waitingWifi[episode.id] = req
            }
            _state.update { st -> st.copy(active = st.active + (episode.id to waitingStatus(req))) }
            return
        }
        synchronized(queue) {
            requests[episode.id] = req
            waitingWifi.remove(episode.id)
            queue.removeAll { it.episode.id == episode.id }
            queue.addLast(req)
        }
        _state.update { st -> st.copy(active = st.active + (episode.id to queuedStatus(req))) }
        startService()
        pump()
    }

    fun retry(episodeId: Long) {
        val req = synchronized(queue) { requests[episodeId] } ?: return
        enqueue(req.anime, req.episode, req.pickKey, req.pickName)
    }

    fun cancel(episodeId: Long) {
        activeTokens.remove(episodeId)
        synchronized(queue) {
            queue.removeAll { it.episode.id == episodeId }
            waitingWifi.remove(episodeId)
            requests.remove(episodeId)
        }
        if (currentId == episodeId) {
            if (pauseForWifiId == episodeId) pauseForWifiId = null
            activeCall?.cancel()
            currentJob?.cancel()
        }
        _state.update { it.copy(active = it.active - episodeId) }
        scope.launch(Dispatchers.IO) {
            runCatching { File(dir, "ep_$episodeId.mp4.part").delete() }
            runCatching { File(dir, "ep_$episodeId.ass.part").delete() }
            if (!_state.value.isDownloaded(episodeId)) {
                runCatching { File(dir, "ep_$episodeId.ass").delete() }
                runCatching { File(dir, "ep_$episodeId.vtt").delete() }
            }
        }
    }

    fun cancelAll() {
        activeTokens.clear()
        pauseForWifiId = null
        synchronized(queue) { queue.clear(); waitingWifi.clear(); requests.clear() }
        activeCall?.cancel()
        currentJob?.cancel()
        _state.update { it.copy(active = emptyMap()) }
        scope.launch(Dispatchers.IO) {
            dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { runCatching { it.delete() } }
        }
    }

    fun delete(episodeId: Long) {
        val removed = _state.value.find(episodeId) ?: return
        _state.update { it.copy(items = it.items.filterNot { d -> d.episodeId == episodeId }) }
        scope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                runCatching { File(dir, removed.videoFile).delete() }
                removed.subFile?.let { runCatching { File(dir, it).delete() } }
                var snapshot: List<DownloadedEpisode> = emptyList()
                _state.update { st ->
                    snapshot = st.items.filterNot { it.episodeId == episodeId }
                    st.copy(items = snapshot)
                }
                writeIndex(snapshot)
            }
        }
    }

    fun deleteAnime(animeId: String) {
        val toRemove = _state.value.items.filter { it.animeId == animeId }
        if (toRemove.isEmpty()) return
        _state.update { it.copy(items = it.items.filterNot { d -> d.animeId == animeId }) }
        scope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                toRemove.forEach { d ->
                    runCatching { File(dir, d.videoFile).delete() }
                    d.subFile?.let { runCatching { File(dir, it).delete() } }
                }
                var snapshot: List<DownloadedEpisode> = emptyList()
                _state.update { st ->
                    snapshot = st.items.filterNot { it.animeId == animeId }
                    st.copy(items = snapshot)
                }
                writeIndex(snapshot)
            }
        }
    }

    fun clearAll() {
        val all = _state.value.items
        _state.update { it.copy(items = emptyList()) }
        scope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                all.forEach { d ->
                    runCatching { File(dir, d.videoFile).delete() }
                    d.subFile?.let { runCatching { File(dir, it).delete() } }
                }
                writeIndex(_state.value.items)
            }
        }
    }

    private fun startService() {
        val intent = Intent(app, DownloadService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        }
    }

    private fun pump() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            while (true) {
                val req = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                currentId = req.episode.id
                val job = launch(Dispatchers.IO) { runDownload(req) }
                currentJob = job
                job.join()
                currentId = null
                currentJob = null
            }
        }
    }

    private suspend fun runDownload(req: PendingDownload) {
        val ep = req.episode
        val videoName = "ep_${ep.id}.mp4"
        val videoPart = File(dir, "$videoName.part")
        val videoFinal = File(dir, videoName)
        var subName: String? = null
        var subFinal: File? = null

        fun setRunning(progress: Float, bytes: Long, total: Long) {
            _state.update { st ->
                if (activeTokens[ep.id] != req.token || ep.id !in st.active) st
                else st.copy(
                    active = st.active + (ep.id to DownloadStatus.Running(
                        ep.id, req.anime.id, req.anime.title, req.anime.poster, ep.number, ep.label,
                        progress, bytes, total,
                    ))
                )
            }
        }

        try {
            if (activeTokens[ep.id] != req.token) return
            if (prefs.wifiOnlyDownloads.first() && !isOnWifi()) {
                synchronized(queue) { waitingWifi[ep.id] = req }
                _state.update { st ->
                    if (activeTokens[ep.id] != req.token || ep.id !in st.active) st
                    else st.copy(active = st.active + (ep.id to waitingStatus(req)))
                }
                return
            }
            setRunning(0f, 0L, 0L)
            val dlQuality = prefs.downloadQuality.first().takeIf { it > 0 } ?: prefs.quality.first()
            val list = a365.episode(ep.id).translations.orEmpty()
            val kind = runCatching { TranslationKind.valueOf(prefs.preferredKind.first()) }.getOrDefault(TranslationKind.VoiceRu)
            val tr = Picker.choose(list, req.pickKey, kind, prefs.favouriteAuthors(), dlQuality)
                ?: throw ApiError("Для этой серии пока нет перевода")
            val embed = a365.embed(tr.id)
            val streams = embed.stream.filter { it.urls.isNotEmpty() }.sortedByDescending { it.height }
            if (streams.isEmpty()) throw ApiError("Anime 365 не отдал видео для скачивания")
            val chosenStream = streams.firstOrNull { it.height <= dlQuality } ?: streams.last()

            // Optional external ASS subtitles (with VTT fallback when provided).
            val subCandidates = listOfNotNull(
                embed.subtitlesUrl?.takeIf { it.isNotBlank() }?.let { it to "ass" },
                embed.subtitlesVttUrl?.takeIf { it.isNotBlank() }?.let { it to "vtt" },
            )
            if (subCandidates.isNotEmpty()) {
                var subErr: Throwable? = null
                for ((url, ext) in subCandidates) {
                    val text = runCatching { a365.subtitles(url) }.onFailure { subErr = it }.getOrNull()
                    if (!text.isNullOrBlank()) {
                        val name = "ep_${ep.id}.$ext"
                        val file = File(dir, name)
                        file.writeText(text)
                        subName = name
                        subFinal = file
                        break
                    }
                }
                if (subFinal == null && tr.kind.isSubs) {
                    throw (subErr as? ApiError ?: ApiError("Не удалось скачать субтитры"))
                }
            }

            if (dir.usableSpace < 120L shl 20) throw ApiError("Недостаточно свободного места на телефоне")

            val referer = a365.baseUrl() + "/"
            val urls = chosenStream.urls.distinct()
            var lastErr: Throwable? = null
            var completed = false
            for (url in urls) {
                if (!currentCoroutineContext().isActive) throw CancellationException()
                val existing = if (videoPart.exists()) videoPart.length() else 0L
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("Referer", referer)
                    .header("User-Agent", a365.userAgent)
                if (existing > 0L) reqBuilder.header("Range", "bytes=$existing-")
                val call = http.newCall(reqBuilder.build())
                activeCall = call
                try {
                    call.execute().use { resp ->
                        if (resp.code == 416 && existing > 0L) {
                            runCatching { videoPart.delete() }
                            throw ApiError("Сервер сбросил позицию докачки")
                        }
                        if (!resp.isSuccessful) throw ApiError("Сервер видео ответил ${resp.code}")
                        val body = resp.body ?: throw ApiError("Пустой ответ видеосервера")
                        val isPartial = resp.code == 206 && existing > 0L
                        val bodyLen = body.contentLength().coerceAtLeast(0L)
                        val total = if (isPartial) {
                            parseContentRangeTotal(resp.header("Content-Range"))
                                ?: if (bodyLen > 0L) existing + bodyLen else 0L
                        } else {
                            bodyLen
                        }
                        val remainingNeed = if (total > 0L) (total - if (isPartial) existing else 0L).coerceAtLeast(0L) else 0L
                        if (remainingNeed > 0L && dir.usableSpace < remainingNeed + (40L shl 20)) {
                            throw ApiError("Не хватает места: нужно ${formatBytes(remainingNeed)}")
                        }
                        var written = if (isPartial) existing else 0L
                        var lastEmit = 0L

                        body.byteStream().use { input ->
                            java.io.FileOutputStream(videoPart, isPartial).use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    if (!currentCoroutineContext().isActive) throw CancellationException()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    written += n
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmit >= 350L) {
                                        lastEmit = now
                                        val p = if (total > 0L) (written.toFloat() / total).coerceIn(0f, 1f) else 0f
                                        setRunning(p, written, total)
                                    }
                                }
                                out.flush()
                            }
                        }
                        if (written <= 0L) {
                            runCatching { videoPart.delete() }
                            throw ApiError("Скачан пустой файл")
                        }
                        if (total > 0L && written < total) throw ApiError("Загрузка оборвалась раньше времени")
                        completed = true
                    }
                    if (completed) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastErr = e
                } finally {
                    if (activeCall === call) activeCall = null
                }
            }
            if (!completed) throw (lastErr ?: ApiError("Не удалось скачать видео"))

            if (videoFinal.exists()) videoFinal.delete()
            if (!videoPart.renameTo(videoFinal)) {
                videoPart.copyTo(videoFinal, overwrite = true)
                videoPart.delete()
            }

            val subLang = if (tr.kind == TranslationKind.SubEn) "en" else if (tr.typeLang.isNotBlank()) tr.typeLang else "ru"
            val saved = DownloadedEpisode(
                episodeId = ep.id,
                animeId = req.anime.id,
                animeTitle = req.anime.title,
                animePoster = req.anime.poster,
                episodeNumber = ep.number,
                episodeLabel = ep.label,
                translationId = tr.id,
                translationKind = tr.kind.name,
                studioKey = tr.pickKey,
                studioName = tr.author,
                subLang = subLang,
                height = chosenStream.height,
                videoFile = videoName,
                subFile = subName,
                sizeBytes = videoFinal.length() + (subFinal?.length() ?: 0L),
                downloadedAt = System.currentTimeMillis(),
            )
            synchronized(queue) { requests.remove(ep.id) }
            val stillCurrent = activeTokens.remove(ep.id, req.token)
            indexMutex.withLock {
                var updated: List<DownloadedEpisode> = emptyList()
                _state.update { st ->
                    updated = listOf(saved) + st.items.filterNot { it.episodeId == ep.id }
                    st.copy(items = updated, active = if (stillCurrent) st.active - ep.id else st.active)
                }
                writeIndex(updated)
            }
        } catch (e: CancellationException) {
            val stillCurrent = activeTokens.remove(ep.id, req.token)
            if (stillCurrent || activeTokens[ep.id] == null) {
                runCatching { videoPart.delete() }
                subFinal?.let { runCatching { it.delete() } }
            }
            if (stillCurrent) _state.update { st -> st.copy(active = st.active - ep.id) }
            throw e
        } catch (e: Throwable) {
            if (pauseForWifiId == ep.id) {
                pauseForWifiId = null
                if (activeTokens[ep.id] == req.token) {
                    synchronized(queue) { waitingWifi[ep.id] = req }
                    _state.update { st ->
                        if (ep.id !in st.active) st else st.copy(active = st.active + (ep.id to waitingStatus(req)))
                    }
                    return
                }
            }
            val stillCurrent = activeTokens.remove(ep.id, req.token)
            if (stillCurrent || activeTokens[ep.id] == null) {
                // Keep non-empty .part files on network failures so Retry resumes with HTTP Range.
                if (videoPart.exists() && videoPart.length() == 0L) runCatching { videoPart.delete() }
                subFinal?.let { runCatching { it.delete() } }
            }
            if (stillCurrent) {
                _state.update { st ->
                    if (ep.id !in st.active) st
                    else st.copy(
                        active = st.active + (ep.id to DownloadStatus.Failed(
                            ep.id, req.anime.id, req.anime.title, req.anime.poster, ep.number, ep.label, e.userMessage(),
                        ))
                    )
                }
            }
        }
    }

    private fun readIndex(): List<DownloadedEpisode> = runCatching {
        if (!indexFile.exists()) emptyList()
        else AppJson.decodeFromString(listSer, indexFile.readText())
    }.getOrDefault(emptyList())

    private fun writeIndex(items: List<DownloadedEpisode>) = runCatching {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(AppJson.encodeToString(listSer, items))
        if (!tmp.renameTo(indexFile)) {
            tmp.copyTo(indexFile, overwrite = true)
            tmp.delete()
        }
    }
}
