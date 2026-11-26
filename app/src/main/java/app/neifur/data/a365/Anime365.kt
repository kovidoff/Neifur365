package app.neifur.data.a365

import android.content.Context
import app.neifur.core.ApiError
import app.neifur.core.AppJson
import app.neifur.core.HttpError
import app.neifur.core.await
import app.neifur.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Watching needs an account: the site only hands out video links to signed-in viewers. */
class SignInRequired : ApiError("Войдите в аккаунт Anime 365, чтобы смотреть")

/** The site plays video only with a paid subscription, and this account has none. */
class SubscriptionRequired : ApiError(MESSAGE) {
    companion object { const val MESSAGE = "Для просмотра нужна подписка Anime 365" }
}

class InvalidCredentials : ApiError("Неверный e-mail или пароль")

private class MirrorBlockedException(msg: String) : IOException(msg)

internal fun isMirrorBlockedBody(body: String): Boolean =
    body.contains("Just a moment", ignoreCase = true) ||
        body.contains("cf-chl", ignoreCase = true) ||
        body.contains("Checking your browser", ignoreCase = true)

internal fun isHtmlResponse(body: String): Boolean {
    val t = body.trimStart()
    return t.startsWith("<!DOCTYPE", ignoreCase = true) || t.startsWith("<html", ignoreCase = true)
}

internal fun hostMatchesDomain(host: String, domain: String): Boolean {
    val h = host.lowercase()
    val d = domain.lowercase()
    return h == d || h.endsWith(".$d")
}

internal fun isHardMirrorFailure(e: Throwable): Boolean =
    e is MirrorBlockedException ||
        e is java.net.UnknownHostException ||
        e is java.net.ConnectException ||
        e is java.net.SocketTimeoutException ||
        e is javax.net.ssl.SSLHandshakeException

enum class MirrorHealth { Ok, Slow, Blocked, Down }
enum class MirrorOrigin { Builtin, Custom }

data class MirrorStatus(
    val host: String, val health: MirrorHealth, val millis: Long,
    val active: Boolean = false, val origin: MirrorOrigin = MirrorOrigin.Builtin,
) {
    val ok: Boolean get() = health == MirrorHealth.Ok || health == MirrorHealth.Slow
}

/**
 * The session cookies, shared by every mirror: all domains are one site behind the scenes, so signing in on
 * one keeps you signed in when the app switches to another. Persisted, so the session survives restarts.
 */
class SessionCookies(context: Context, private val isOurs: (String) -> Boolean) : CookieJar {
    private val store = context.getSharedPreferences("a365_session", Context.MODE_PRIVATE)
    private val jar = ConcurrentHashMap<String, String>().apply {
        store.all.forEach { (k, v) -> if (v is String) put(k, v) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!isOurs(url.host)) return
        val now = System.currentTimeMillis()
        cookies.forEach { c -> if (c.expiresAt < now || c.value.isEmpty() || c.value == "deleted") jar.remove(c.name) else jar[c.name] = c.value }
        persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!isOurs(url.host)) return emptyList()
        return jar.mapNotNull { (k, v) -> runCatching { Cookie.Builder().name(k).value(v).domain(url.host).path("/").build() }.getOrNull() }
    }

    operator fun get(name: String): String? = jar[name]
    operator fun set(name: String, value: String) { jar[name] = value; persist() }
    fun remove(name: String) { jar.remove(name); persist() }

    /** Takes over cookies from a sign-in made in the in-app browser ("a=1; b=2"). */
    fun import(header: String) {
        header.split(';').map { it.trim() }.filter { '=' in it }.forEach { jar[it.substringBefore('=')] = it.substringAfter('=') }
        persist()
    }

    fun header(): String = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun clear() { jar.clear(); persist() }

    private fun persist() { store.edit().clear().apply { jar.forEach { (k, v) -> putString(k, v) } }.apply() }
}

class Anime365(context: Context, base: OkHttpClient, private val prefs: Prefs) {
    companion object {
        /** Official addresses. smotret-anime.app is the one the site recommends for Russia (no VPN needed). */
        val BUILTIN = listOf("smotret-anime.app", "smotret-anime.co", "smotretanime.co", "smotret-anime.com", "smotret-anime.org")
        private val EXTRA_SITE_HOSTS = listOf("anime365.ru", "smotret-anime.ru")
        const val CARD_FIELDS = "id,myAnimeListId,myAnimeListScore,numberOfEpisodes,season,year,type,typeTitle,isAiring,titles,posterUrl,posterUrlSmall,genres"
        private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Mobile Safari/537.36"
    }

    @Volatile private var known: Set<String> = BUILTIN.toSet()

    /** Whether [host] is one of the site's addresses (built-in or added by the viewer). */
    fun isSiteHost(host: String?): Boolean =
        host != null && (known.any { hostMatchesDomain(host, it) } || EXTRA_SITE_HOSTS.any { hostMatchesDomain(host, it) })
    val cookies = SessionCookies(context) { h ->
        known.any { hostMatchesDomain(h, it) } || EXTRA_SITE_HOSTS.any { hostMatchesDomain(h, it) }
    }

    /**
     * The phone's own browser identity (WebView's, without the "wv" mark). The in-app site page uses the same one,
     * so a session signed in there is the same browser to the site when the app carries it on.
     */
    val userAgent: String by lazy {
        runCatching { android.webkit.WebSettings.getDefaultUserAgent(context).replace("; wv", "") }.getOrDefault(BROWSER_UA)
    }

    private val http = base.newBuilder()
        .cookieJar(cookies)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
        .build()
    private val probeHttp = http.newBuilder().callTimeout(9, TimeUnit.SECONDS).build()

    // --- mirrors ---------------------------------------------------------------------------------------
    private val resolveLock = Mutex()
    @Volatile private var lastProbe: List<MirrorStatus> = emptyList()

    suspend fun mirrorList(): List<Pair<String, MirrorOrigin>> {
        val custom = prefs.customMirrors.first().filterNot { it in BUILTIN }
        known = (BUILTIN + custom).toSet()
        return BUILTIN.map { it to MirrorOrigin.Builtin } + custom.map { it to MirrorOrigin.Custom }
    }

    suspend fun probe(host: String, origin: MirrorOrigin = MirrorOrigin.Builtin): MirrorStatus = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        runCatching {
            probeHttp.newCall(Request.Builder().url("https://$host/api/series?limit=1&fields=id").build()).await().use { r ->
                val body = r.body?.string().orEmpty()
                val ms = System.currentTimeMillis() - t0
                val health = when {
                    body.contains("Just a moment") || body.contains("cf-chl") -> MirrorHealth.Blocked
                    r.isSuccessful && body.startsWith("{\"data\"") -> if (ms > 2500) MirrorHealth.Slow else MirrorHealth.Ok
                    else -> MirrorHealth.Down
                }
                MirrorStatus(host, health, ms, origin = origin)
            }
        }.getOrElse { MirrorStatus(host, MirrorHealth.Down, System.currentTimeMillis() - t0, origin = origin) }
    }

    suspend fun probeAll(): List<MirrorStatus> = coroutineScope {
        mirrorList().map { (h, o) -> async { probe(h, o) } }.awaitAll().also { lastProbe = it }
    }

    /** The address requests go to: the saved one, or (first launch, or it stopped working) the fastest live one. */
    suspend fun host(): String {
        prefs.mirror.first()?.let {
            if (it !in known) known = known + it
            return it
        }
        return resolveMirror()
    }

    suspend fun resolveMirror(exclude: String? = null, forceReprobe: Boolean = false): String = resolveLock.withLock {
        if (!forceReprobe) prefs.mirror.first()?.takeIf { it != exclude }?.let { return it }
        val live = probeAll().filter { it.ok && it.host != exclude }
        val manual = !prefs.mirrorAuto.first()
        val cur = prefs.mirror.first()
        val pick = live.firstOrNull { manual && exclude == null && !forceReprobe && it.host == cur }
            ?: live.filter { it.health == MirrorHealth.Ok }.minByOrNull { it.millis }
            ?: live.minByOrNull { it.millis }
            ?: throw ApiError("Не удалось связаться с Anime 365. Проверьте интернет или VPN")
        if (!manual && exclude != null) {
            // Auto mode: save new working mirror; manual mode keeps user's pick unless cur == null.
            prefs.setMirror(pick.host)
        } else if (cur == null || (manual && exclude == null)) {
            prefs.setMirror(pick.host)
        }
        pick.host
    }

    /** Re-checks the active mirror (e.g. after a network switch) and replaces it quietly if blocked, down, or degraded. */
    suspend fun ensureMirrorWorking() {
        val auto = prefs.mirrorAuto.first()
        val cur = prefs.mirror.first()
        if (!auto) {
            if (cur == null) runCatching { resolveMirror() }
            return
        }
        if (cur == null) {
            runCatching { resolveMirror() }
            return
        }
        val st = probe(cur)
        if (st.health == MirrorHealth.Ok) return
        runCatching { resolveMirror(exclude = if (!st.ok) cur else null, forceReprobe = true) }
    }

    suspend fun pick(host: String) {
        if (host !in known) known = known + host
        prefs.setMirrorAuto(false)
        prefs.setMirror(host)
    }

    suspend fun removeCustom(host: String) {
        prefs.setCustomMirrors(prefs.customMirrors.first() - host)
        if (prefs.mirror.first() == host) prefs.setMirror(null)
    }

    /** Runs [block] against the current mirror; a dead or Cloudflare-blocked mirror is swapped for a live one and retried. */
    private suspend fun <T> mirrored(block: suspend (base: String) -> T): T {
        val first = host()
        try {
            return block("https://$first")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val mirrorDown = e is MirrorBlockedException || (e is IOException && (e !is HttpError || e.code >= 500))
            if (!mirrorDown) throw e
            // Transient socket drop: retry once quickly; hard failures (DNS/connect/timeout/SSL/Cloudflare) fail over immediately.
            if (!isHardMirrorFailure(e)) {
                try {
                    return block("https://$first")
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (_: Exception) {
                }
            }
            val next = resolveMirror(exclude = first, forceReprobe = false)
            return block("https://$next")
        }
    }

    suspend fun baseUrl(): String = "https://${host()}"

    // --- transport -------------------------------------------------------------------------------------
    private suspend fun text(req: Request, allow403: Boolean = false): String = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val call = runCatching { http.newCall(req).await() }
            .onFailure { if (dumpDir != null) android.util.Log.w("A365", "${req.method} ${req.url.encodedPath} failed: $it") }
            .getOrThrow()
        call.use { r ->
            val body = r.body?.string().orEmpty()
            if (dumpDir != null) android.util.Log.d("A365", "${req.method} ${req.url.encodedPath} → ${r.code} in ${System.currentTimeMillis() - t0} ms")
            if (isHtmlResponse(body) && isMirrorBlockedBody(body)) throw MirrorBlockedException("Cloudflare challenge on ${req.url.host}")
            if (!r.isSuccessful && !(allow403 && r.code == 403)) throw HttpError(r.code, body.take(300))
            // Debug builds keep the last copy of each site page, to check the parsers against real markup.
            if (dumpDir != null && !req.url.encodedPath.startsWith("/api")) runCatching {
                val name = (req.url.encodedPath.trim('/').ifEmpty { "index" } + "_" + req.url.query.orEmpty()).replace(Regex("[^\\w.-]"), "_").take(80)
                java.io.File(dumpDir, "$name.html").writeText(body)
            }
            body
        }
    }

    private val dumpDir: java.io.File? =
        if (app.neifur.BuildConfig.DEBUG) java.io.File(context.filesDir, "pages").apply { mkdirs() } else null

    private suspend fun <T> api(path: String, ser: KSerializer<T>): T = mirrored { base ->
        val raw = text(Request.Builder().url("$base/api$path").header("Accept", "application/json").build(), allow403 = true)
        if (isHtmlResponse(raw)) throw MirrorBlockedException("HTML response instead of JSON on $base")
        val root = runCatching { AppJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw ApiError("Anime 365 ответил что-то непонятное")
        root["error"]?.jsonObject?.let { err ->
            val code = err["code"]?.jsonPrimitive?.intOrNull ?: 0
            val msg = runCatching { err["message"]!!.jsonPrimitive.content }.getOrNull().orEmpty()
            when {
                code == 403 && msg.contains("login", true) -> throw sessionLost()
                msg.contains("subscription", true) -> throw SubscriptionRequired()
                code == 404 -> throw ApiError("Не найдено на Anime 365")
                else -> throw ApiError(msg.ifBlank { "Ошибка Anime 365 ($code)" })
            }
        }
        AppJson.decodeFromJsonElement(ser, root["data"] ?: throw ApiError("Пустой ответ Anime 365"))
    }

    private suspend fun page(path: String): String = mirrored { base -> text(Request.Builder().url(base + path).build()) }

    /**
     * Site forms check a signed token the server issues in the `csrf` cookie. The cookie holds it URL-encoded
     * ("…%3D%3D"); the form field must carry the decoded value, or the site answers 400. Without a token yet,
     * the login page is opened first to get one; a rejected (stale) token is replaced once and the form resent.
     */
    private suspend fun post(path: String, form: Map<String, String>): String = mirrored { base ->
        suspend fun send(): String {
            if (cookies["csrf"] == null) text(Request.Builder().url("$base/users/login").build())
            val raw = cookies["csrf"] ?: throw ApiError("Anime 365 не выдал ключ формы, попробуйте вход через сайт")
            val csrf = java.net.URLDecoder.decode(raw, "UTF-8")
            val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) }; add("csrf", csrf) }.build()
            return text(Request.Builder().url(base + path).post(body).build())
        }
        try {
            send()
        } catch (e: HttpError) {
            if (e.code != 400) throw e
            cookies.remove("csrf")
            send()
        }
    }

    // --- catalog ---------------------------------------------------------------------------------------
    private val seriesList = ListSerializer(Series.serializer())

    suspend fun catalog(
        query: String = "", offset: Int = 0, limit: Int = 30, type: String? = null,
        airing: Boolean = false, year: Int? = null, genre: Int? = null,
    ): List<Series> {
        val q = buildList {
            add("fields=$CARD_FIELDS"); add("limit=$limit"); add("offset=$offset")
            if (query.isNotBlank()) add("query=" + java.net.URLEncoder.encode(query.trim(), "UTF-8"))
            type?.let { add("type=$it") }
            if (airing) add("isAiring=1")
            year?.let { add("year=$it") }
            genre?.let { add("chips=" + java.net.URLEncoder.encode("genre=$it", "UTF-8")) }
        }.joinToString("&")
        return api("/series?$q", seriesList)
    }

    private val seriesCache = ConcurrentHashMap<Long, Pair<Long, Series>>()

    /** Full title with its episode list. Cached briefly: going back and forth between pages stays instant. */
    suspend fun series(id: Long, force: Boolean = false): Series {
        seriesCache[id]?.takeIf { !force && System.currentTimeMillis() - it.first < 5 * 60_000 }?.let { return it.second }
        return api("/series/$id", Series.serializer()).also { seriesCache[id] = System.currentTimeMillis() to it }
    }

    suspend fun byMyAnimeList(malId: Long): Series? =
        api("/series?myAnimeListId=$malId&fields=$CARD_FIELDS&limit=1", seriesList).firstOrNull()

    /** Cards for many titles in one request (lists from the site carry only ids and names). */
    suspend fun cards(ids: List<Long>): List<Series> = coroutineScope {
        ids.distinct().chunked(40).map { chunk ->
            async {
                translationGate.withPermit {
                    runCatching {
                        api("/series?fields=$CARD_FIELDS&limit=${chunk.size}&" + chunk.joinToString("&") { "id[]=$it" }, seriesList)
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten()
    }

    suspend fun episode(id: Long): Episode = api("/episodes/$id", Episode.serializer())

    private val translationList = ListSerializer(Translation.serializer())
    private val translationGate = kotlinx.coroutines.sync.Semaphore(3)

    /**
     * Every translation of these episodes, trimmed to what the studio picker needs. Asked in batches of episodes
     * (the site answers at most 1000 rows per request, so each batch is paged), three batches at a time.
     */
    suspend fun translations(episodeIds: List<Long>): List<Translation> = coroutineScope {
        val fields = "id,episodeId,type,typeKind,typeLang,authorsSummary,height,priority,isActive,qualityType"
        episodeIds.distinct().chunked(20).map { chunk ->
            async {
                translationGate.withPermit {
                    val ids = chunk.joinToString("&") { "episodeId[]=$it" }
                    val out = ArrayList<Translation>()
                    var offset = 0
                    while (true) {
                        val page = api("/translations?limit=1000&offset=$offset&fields=$fields&$ids", translationList)
                        out += page
                        if (page.size < 1000) break
                        offset += page.size
                    }
                    out
                }
            }
        }.awaitAll().flatten()
    }

    // --- playback --------------------------------------------------------------------------------------
    suspend fun embed(translationId: Long): Embed = api("/translations/embed/$translationId", Embed.serializer()).also { e ->
        if (dumpDir != null) android.util.Log.d("A365", "embed $translationId: " + e.stream.joinToString { "${it.height}p×${it.urls.size}" })
    }

    /** Subtitle files are served to the signed-in session only, so they're fetched here rather than by the player. */
    suspend fun subtitles(url: String): String = mirrored { base ->
        val body = text(Request.Builder().url(SitePages.resolve(base, url)).build())
        if (isHtmlResponse(body)) throw ApiError("Сервер вернул HTML вместо субтитров")
        body
    }

    // --- account ---------------------------------------------------------------------------------------
    private val commentCache = ConcurrentHashMap<Long, String>()

    suspend fun login(email: String, password: String): Profile {
        val html = post("/users/login", mapOf("LoginForm[username]" to email.trim(), "LoginForm[password]" to password, "dynpage" to "1", "yt0" to ""))
        if (SitePages.loginFailed(html)) throw InvalidCredentials()
        return profile() ?: throw ApiError("Не получилось войти. Попробуйте вход через сайт")
    }

    /** The signed-in viewer, or null when the session is missing or expired. */
    suspend fun profile(): Profile? {
        if (cookies.header().isEmpty()) return null
        return mirrored { base -> SitePages.profile(text(Request.Builder().url("$base/users/profile?dynpage=1").build()), base) }
    }

    fun logout() {
        cookies.clear()
        commentCache.clear()
    }

    /** Called when the site turns out to have ended a session the app still holds (password changed, expired). */
    @Volatile var onSessionLost: (() -> Unit)? = null

    private fun sessionLost(): SignInRequired {
        if (cookies.header().isNotEmpty()) onSessionLost?.invoke()
        return SignInRequired()
    }

    /** Every list of the viewer at once, each row with its status. */
    suspend fun allLists(userId: Long): List<ListEntry> {
        val html = page("/users/$userId/list?dynpage=1")
        if (SitePages.isSignedOut(html)) throw sessionLost()
        return SitePages.list(html)
    }

    suspend fun list(userId: Long, status: ListStatus): List<ListEntry> {
        val html = page("/users/$userId/list/${status.path}?dynpage=1")
        if (SitePages.isSignedOut(html)) throw sessionLost()
        return SitePages.list(html)
    }

    suspend fun listRecord(seriesId: Long): ListRecord {
        val html = page("/animelist/edit/$seriesId?mode=mini")
        if (SitePages.isSignedOut(html)) throw sessionLost()
        return SitePages.listRecord(html).also {
            if (it.status == null) commentCache.remove(seriesId) else commentCache[seriesId] = it.comment
        }
    }

    /** [status] null removes the title from the list. */
    suspend fun setListRecord(seriesId: Long, status: ListStatus?, watched: Int, score: Int?, comment: String? = null) {
        if (status == null) commentCache.remove(seriesId)
        val resolvedComment = if (status == null) {
            ""
        } else {
            comment ?: commentCache[seriesId] ?: runCatching { listRecord(seriesId).comment }.getOrDefault("").also {
                commentCache[seriesId] = it
            }
        }
        val html = try {
            post(
                "/animelist/edit/$seriesId?mode=mini",
                mapOf(
                    "UsersRates[score]" to (score ?: 0).toString(), "UsersRates[episodes]" to watched.toString(),
                    "UsersRates[status]" to (status?.id ?: 99).toString(), "UsersRates[comment]" to resolvedComment,
                ),
            )
        } catch (e: HttpError) {
            if (e.code == 403) throw sessionLost() else throw e
        }
        if (SitePages.isSignedOut(html)) throw sessionLost()
    }

    /** Marks the episode of this translation as watched; the site moves the list counter itself. */
    suspend fun markWatched(translationId: Long) {
        val html = try {
            post("/translations/watched/$translationId", emptyMap())
        } catch (e: HttpError) {
            if (e.code == 403) throw sessionLost() else throw e
        }
        if (SitePages.isSignedOut(html)) throw sessionLost()
    }

    // --- video channels ----------------------------------------------------------------------------------

    /** The account's video channels and which one it uses, from the site's connection test page. */
    suspend fun channels(): ChannelSetup =
        ChannelPage.parse(page("/diagnostic")) ?: throw ApiError("Не получилось узнать каналы Anime 365")

    /** Switches the account to channel [id]: every video link after this comes from it. */
    suspend fun setChannel(id: Int) {
        val raw = post("/diagnostic/setChannel", mapOf("channel" to id.toString()))
        val ok = runCatching { AppJson.parseToJsonElement(raw).jsonObject["ok"]?.jsonPrimitive?.content == "true" }.getOrDefault(false)
        if (!ok) throw ApiError("Anime 365 не переключил канал")
    }

    private val channelHttp by lazy {
        http.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(14, TimeUnit.SECONDS).build()
    }

    /**
     * Downloads [seconds] of the channel's test file (after the first byte, so a slow handshake doesn't count
     * against it) and returns the speed. A channel the network blocks answers, sends ~20 KB and goes silent, so it
     * comes out at a few KB/s rather than failing outright.
     */
    suspend fun measure(channel: VideoChannel, seconds: Int = 5, maxBytes: Long = 8_000_000): ChannelSpeed = withContext(Dispatchers.IO) {
        suspend fun one(url: String): Double {
            val t0 = System.nanoTime()
            var first = 0L
            var got = 0L
            var code = 0
            val bust = url + (if ('?' in url) "&" else "?") + "_=" + System.nanoTime().toString(36)
            val err = runCatching {
                channelHttp.newCall(Request.Builder().url(bust).header("Range", "bytes=0-${maxBytes - 1}").build()).await().use { r ->
                    code = r.code
                    if (!r.isSuccessful) return@use
                    val src = r.body?.source() ?: return@use
                    val buf = okio.Buffer()
                    while (got < maxBytes) {
                        val n = src.read(buf, 64 * 1024)
                        if (n < 0) break
                        if (first == 0L) first = System.nanoTime()
                        got += n; buf.clear()
                        if (System.nanoTime() - first > seconds * 1_000_000_000L) break
                    }
                }
            }.exceptionOrNull()
            val end = System.nanoTime()
            val sec = if (first > 0) (end - first) / 1e9 else (end - t0) / 1e9
            val speed = if (sec > 0.05) got / sec else if (got > 0) got / 0.05 else 0.0
            if (dumpDir != null) android.util.Log.d(
                "A365", "channel ${channel.id} ${java.net.URI(url).host}: $code, ${got / 1024} KB, ttfb ${if (first > 0) (first - t0) / 1_000_000 else -1} ms, " +
                    "${(speed / 1024).toInt()} KB/s" + (err?.let { " ($it)" } ?: ""),
            )
            return speed
        }
        val main = one(channel.testUrl)
        val best = if (main < ChannelSpeed.WORKS && channel.fallbackUrl != null) maxOf(main, one(channel.fallbackUrl)) else main
        ChannelSpeed(best)
    }

}
