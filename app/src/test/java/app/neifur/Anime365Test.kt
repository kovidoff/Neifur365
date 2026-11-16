package app.neifur

import app.neifur.core.AppJson
import app.neifur.data.a365.Episode
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.Picker
import app.neifur.data.a365.Series
import app.neifur.data.a365.SitePages
import app.neifur.data.a365.Translation
import app.neifur.data.a365.TranslationKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
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

    @Test fun authorDropsReleaseNotes() {
        val t = Translation(1, "Freedub Studio [ТВ] (BD)", typeKind = "voice", typeLang = "ru")
        assertEquals("Freedub Studio", t.author)
    }

    @Test fun episodeLinksGiveSeriesAndEpisodeIds() {
        assertEquals(30414L to 291395L, SitePages.ids("https://smotret-anime.app/catalog/sousou-no-frieren-30414/1-seriya-291395"))
        assertNull(SitePages.ids("https://smotret-anime.app/users/profile"))
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
        assertEquals(true, SitePages.profile(page("<p>Оплачена до 01.03.2027.<br>ID аккаунта: 307957</p>"), "https://anime365.ru")!!.subscribed)
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
        """.trimIndent()
        val r = SitePages.listRecord(html)
        assertEquals(ListStatus.Watching, r.status); assertEquals(3, r.watched); assertEquals(8, r.score)
        assertNull(SitePages.listRecord("<button>Добавить в список</button>").status)
    }
}
