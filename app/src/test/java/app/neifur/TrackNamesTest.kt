package app.neifur

import app.neifur.player.SubKind
import app.neifur.player.Track
import app.neifur.player.TrackNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackNamesTest {
    private fun d(lang: String?, title: String?, type: String = "sub", forced: Boolean = false) =
        TrackNames.describe(Track(1, type, title, lang, "ass", false, forced = forced))

    @Test fun eraiAudio() {
        val i = d("ja", "[Erai-raws]_AAC_CR", "audio")
        assertEquals("Японский", i.name); assertEquals("Crunchyroll", i.source); assertNull(i.note); assertNull(i.region)
    }

    @Test fun crunchyrollSubs() {
        d("pt-BR", "Brazilian_CR").let { assertEquals("Португальский", it.name); assertEquals("Бразилия", it.region); assertEquals("Crunchyroll", it.source); assertNull(it.note) }
        d("es-419", "Latin_America_CR").let { assertEquals("Испанский", it.name); assertEquals("Латинская Америка", it.region); assertNull(it.note) }
        d("es", "CR").let { assertEquals("Испанский", it.name); assertNull(it.region); assertEquals("Crunchyroll", it.source) }
        d("ar", "CR").let { assertEquals("Арабский", it.name) }
        d("ru", "CR_Russian").let { assertEquals("Русский", it.name); assertNull(it.note) }
    }

    @Test fun kinds() {
        assertEquals(SubKind.Signs, d("en", "English (Signs) [Generated]").kind)
        assertEquals(SubKind.Signs, d("eng", "Signs & Songs").kind)
        assertEquals(SubKind.Full, d("eng", "Full Subtitles [SubsPlease]").kind)
        assertEquals(SubKind.Captions, d("en", "English (CC)").kind)
        assertEquals(SubKind.Signs, d("en", "English", forced = true).kind)
    }

    @Test fun languageFromTitle() {
        assertEquals("Русский", d(null, "Русские субтитры").name)
        assertEquals("Японский", d("und", "日本語", "audio").name)
        assertEquals("Дорожка 1", d(null, null, "audio").name)
    }

    @Test fun meaningfulNoteSurvives() {
        assertEquals("Commentary", d("en", "English Commentary", "audio").note)
    }
}
