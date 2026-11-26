package app.neifur

import app.neifur.core.AppJson
import app.neifur.data.a365.ChannelSpeed
import app.neifur.data.a365.Episode
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.Picker
import app.neifur.data.a365.Series
import app.neifur.data.a365.SitePages
import app.neifur.data.a365.Translation
import app.neifur.data.a365.TranslationKind
import app.neifur.data.a365.VideoChannel
import app.neifur.data.a365.hostMatchesDomain
import app.neifur.data.a365.isHardMirrorFailure
import app.neifur.data.a365.isHtmlResponse
import app.neifur.data.a365.isMirrorBlockedBody
import app.neifur.data.a365.pickBetterChannel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing and translation choice against real Anime 365 responses (saved in test resources). */
class Anime365Test {
    private fun <T> data(name: String, ser: kotlinx.serialization.KSerializer<T>): T {
        val raw = javaClass.getResource("/a365/$name")!!.readText()
        return AppJson.decodeFromJsonElement(ser, AppJson.parseToJsonElement(raw).jsonObject["data"]!!)
    }

    private val episode by lazy { data("episode-frieren-1.json", Episode.serializer()) }

    @Test fun seriesParsesWithEpisodes() {
        val s = data("series-frieren.json", Series.serializer())
        assertEquals(30414L, s.id)
        assertEquals(52991L, s.myAnimeListId)
        assertTrue(s.title.isNotBlank())
        assertNotNull(s.description)
        // Trailers ("preview") are not episodes to watch.
        assertTrue(s.playable.none { it.episodeType == "preview" })
        assertEquals(28, s.playable.count { it.episodeType == "tv" })
        assertEquals(1, s.playable.first().number)
    }

    @Test fun listParsesTrimmedCards() {
        val list = data("list-airing.json", ListSerializer(Series.serializer()))
        assertEquals(5, list.size)
        assertTrue(list.all { it.airing && it.posterUrl != null })
    }

    @Test fun translationsAreGroupedByKind() {
        val all = episode.translations.orEmpty()
        assertTrue(all.size > 50)
        val options = Picker.options(all, 1080)
        assertTrue(options.keys.first() == TranslationKind.VoiceRu)
        // One row per studio: TV and BD uploads of the same studio collapse into one.
        val voices = options.getValue(TranslationKind.VoiceRu)
        assertEquals(voices.size, voices.map { it.pickKey }.distinct().size)
    }

    @Test fun defaultPickIsARussianVoiceOver() {
        val t = Picker.choose(episode.translations.orEmpty(), null, TranslationKind.VoiceRu, emptyList(), 1080)!!
        assertEquals(TranslationKind.VoiceRu, t.kind)
        assertTrue(t.height <= 1080)
    }

    @Test fun pickedStudioFollowsAndPreferredKindWorks() {
        val all = episode.translations.orEmpty()
        val libria = all.first { it.kind == TranslationKind.VoiceRu && it.author.contains("AniLibria", true) }
        assertEquals(libria.pickKey, Picker.choose(all, libria.pickKey, TranslationKind.VoiceRu, emptyList(), 1080)!!.pickKey)
        assertEquals(TranslationKind.SubRu, Picker.choose(all, null, TranslationKind.SubRu, emptyList(), 1080)!!.kind)
        // A studio that isn't on this episode falls back to the preferred kind instead of failing.
        assertEquals(TranslationKind.VoiceRu, Picker.choose(all, "VoiceRu|nobody studio", TranslationKind.VoiceRu, emptyList(), 1080)!!.kind)
    }

    @Test fun qualityNeverExceedsTheSettingWhenALowerOneExists() {
        val a = Translation(1, "Studio", type = "voiceRu", typeKind = "voice", typeLang = "ru", height = 2160, priority = 5)
        val b = a.copy(id = 2, height = 1080, priority = 1)
        assertEquals(2L, Picker.best(listOf(a, b), 1080)!!.id)
        assertEquals(1L, Picker.best(listOf(a, b), 2160)!!.id)
    }

    @Test fun pickerBestHandles4kBdVs720AndUnknownHeight() {
        val bd4k = Translation(1, "Studio", type = "voiceRu", typeKind = "voice", typeLang = "ru", qualityType = "bd", height = 2160, priority = 5)
        val tv720 = bd4k.copy(id = 2, qualityType = "tv", height = 720, priority = 1)
        assertEquals(1L, Picker.best(listOf(bd4k, tv720), 1080)!!.id)

        val unknown = Translation(10, "Studio", type = "voiceRu", typeKind = "voice", typeLang = "ru", height = 0, priority = 99)
        val fullHd = Translation(11, "Studio", type = "voiceRu", typeKind = "voice", typeLang = "ru", height = 1080, priority = 1)
        assertEquals(11L, Picker.best(listOf(unknown, fullHd), 720)!!.id)
    }

    @Test fun authorDropsReleaseNotes() {
        val t = Translation(1, "Freedub Studio [ТВ] (BD)", typeKind = "voice", typeLang = "ru")
        assertEquals("Freedub Studio", t.author)
    }

    @Test fun episodeLinksGiveSeriesAndEpisodeIds() {
        assertEquals(30414L to 291395L, SitePages.ids("https://smotret-anime.app/catalog/sousou-no-frieren-30414/1-seriya-291395"))
        assertNull(SitePages.ids("https://smotret-anime.app/users/profile"))
    }

    @Test fun catalogDeepLinksResolveSeriesId() {
        assertEquals(30414L, SitePages.seriesId("https://anime365.ru/catalog/sousou-no-frieren-30414"))
        assertEquals(30414L, SitePages.seriesId("https://smotret-anime.app/catalog/sousou-no-frieren-30414/1-seriya-291395?ref=tg#top"))
        assertEquals(30414L, SitePages.seriesId("https://smotret-anime.org/catalog/30414"))
        assertNull(SitePages.seriesId("https://anime365.ru/users/profile"))
        assertNull(SitePages.seriesId("https://anime365.ru/catalog/"))
    }

    @Test fun signedOutPagesAreRecognised() {
        assertTrue(SitePages.isSignedOut("<title>Вход - Anime 365</title>"))
        assertNull(SitePages.profile("<form>Вход по паролю</form>", "https://smotret-anime.app"))
    }

    @Test fun subscriptionComesFromTheProfilePage() {
        fun page(card: String) = """<div class="m-small-title">Testing789</div>
            <div class="card"><div class="card-content"><h2 class="no-margin-top">Подписка</h2>$card</div></div>"""
        val none = SitePages.profile(page("<p>Не оплачена.<br>ID аккаунта: 307957</p>"), "https://anime365.ru")!!
        assertEquals(307957L, none.id)
        assertEquals(false, none.subscribed)
        assertNull(none.subscribedUntil)

        val paid = SitePages.profile(page("<p>Оплачена до 01.03.2027.<br>ID аккаунта: 307957</p>"), "https://anime365.ru")!!
        assertEquals(true, paid.subscribed)
        assertEquals("2027-03-01", paid.subscribedUntil)

        assertEquals("2027-04-05", SitePages.parseSubscriptionUntil("Активна до 5 апреля 2027 года"))
        assertEquals("2027-05-12", SitePages.parseSubscriptionUntil("Действует до 2027-05-12 23:59"))
        assertNull(SitePages.profile("<p>ID аккаунта: 307957</p>", "https://anime365.ru")!!.subscribed)
    }

    @Test fun listPageParses() {
        val html = """
            <div class="card m-animelist-card"><table>
            <tr class="m-animelist-item" data-id="30414"><td><a href="/catalog/x-30414">Фрирен</a></td>
              <td data-name="episodes">5 / 28</td><td data-name="score">9</td></tr>
            <tr class="m-animelist-item" data-id="2400"><td><a href="/catalog/y-2400">Алхимик</a></td>
              <td data-name="episodes">0 / ?</td><td data-name="score"></td></tr>
            </table></div>
        """.trimIndent()
        val l = SitePages.list(html)
        assertEquals(2, l.size)
        assertEquals(5, l[0].watched); assertEquals(28, l[0].total); assertEquals(9, l[0].score)
        assertNull(l[1].total); assertNull(l[1].score)
    }

    @Test fun listRecordParses() {
        val html = """
            <input id="UsersRates_episodes" value="3">
            <select id="UsersRates_status"><option value="0">План</option><option value="1" selected>Смотрю</option></select>
            <select id="UsersRates_score"><option value="0">-</option><option value="8" selected>8</option></select>
            <textarea id="UsersRates_comment" name="UsersRates[comment]">Шедевр 10/10</textarea>
        """.trimIndent()
        val r = SitePages.listRecord(html)
        assertEquals(ListStatus.Watching, r.status); assertEquals(3, r.watched); assertEquals(8, r.score)
        assertEquals("Шедевр 10/10", r.comment)
        assertNull(SitePages.listRecord("<button>Добавить в список</button>").status)
    }

    @Test fun hostMatchingRejectsSpoofedSuffixesAndMatchesSubdomains() {
        assertTrue(hostMatchesDomain("smotret-anime.app", "smotret-anime.app"))
        assertTrue(hostMatchesDomain("www.anime365.ru", "anime365.ru"))
        assertTrue(hostMatchesDomain("CDN.SMOTRET-ANIME.APP", "smotret-anime.app"))
        assertFalse(hostMatchesDomain("evilsmotret-anime.app", "smotret-anime.app"))
        assertFalse(hostMatchesDomain("anime365.ru.evil.com", "anime365.ru"))
    }

    @Test fun mirrorBlockAndHtmlDetection() {
        assertTrue(isMirrorBlockedBody("<title>Just a moment...</title>"))
        assertTrue(isMirrorBlockedBody("<script src=\"/cdn-cgi/challenge-platform/cf-chl.js\">"))
        assertTrue(isMirrorBlockedBody("Checking your browser before accessing"))
        assertFalse(isMirrorBlockedBody("""{"data":[{"id":1}]}"""))

        assertTrue(isHtmlResponse("<!DOCTYPE html><html><body>Block</body></html>"))
        assertTrue(isHtmlResponse("  <html><head></head></html>"))
        assertFalse(isHtmlResponse("""{"error":{"code":403,"message":"login required"}}"""))

        assertTrue(isHardMirrorFailure(java.net.UnknownHostException("nxdomain")))
        assertTrue(isHardMirrorFailure(java.net.ConnectException("refused")))
        assertTrue(isHardMirrorFailure(java.net.SocketTimeoutException("timeout")))
        assertTrue(isHardMirrorFailure(javax.net.ssl.SSLHandshakeException("handshake")))
        assertFalse(isHardMirrorFailure(java.io.IOException("stream reset")))
    }

    @Test fun channelSelectionRules() {
        val ch1 = VideoChannel(1, "Ch1", "(Cloudflare)", "https://a/1", null)
        val ch2 = VideoChannel(2, "Ch2", "(Bunny)", "https://b/2", null)
        val broken = ChannelSpeed(10_000.0)
        val ok = ChannelSpeed(600_000.0)
        val faster = ChannelSpeed(900_000.0)
        val slightlyFaster = ChannelSpeed(650_000.0)

        // Broken current -> switch to working best
        assertEquals(ch2, pickBetterChannel(1, broken, ch2, ok, preferSwitch = false))
        // Working current in background check -> stay
        assertNull(pickBetterChannel(1, ok, ch2, faster, preferSwitch = false))
        // Working slow current on open failure + >1.25x faster -> switch
        assertEquals(ch2, pickBetterChannel(1, ok, ch2, faster, preferSwitch = true))
        // Working current on open failure + only slightly faster -> stay
        assertNull(pickBetterChannel(1, ok, ch2, slightlyFaster, preferSwitch = true))
        // Same channel -> never switch
        assertNull(pickBetterChannel(2, ok, ch2, faster, preferSwitch = true))
        // No working best -> never switch
        assertNull(pickBetterChannel(1, broken, ch2, broken, preferSwitch = true))
    }
}
