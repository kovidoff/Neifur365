package app.neifur

import app.neifur.core.AppJson
import app.neifur.data.a365.Episode
import app.neifur.data.a365.Series
import app.neifur.data.a365.Studio
import app.neifur.data.a365.Studios
import app.neifur.data.a365.Translation
import app.neifur.data.a365.TranslationKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Studios of a whole title, built from the real translation list of Frieren (all episodes). */
class StudiosTest {
    private fun <T> data(name: String, ser: kotlinx.serialization.KSerializer<T>): T {
        val raw = javaClass.getResource("/a365/$name")!!.readText()
        return AppJson.decodeFromJsonElement(ser, AppJson.parseToJsonElement(raw).jsonObject["data"]!!)
    }

    private val episodes by lazy { data("series-frieren.json", Series.serializer()).playable }
    private val translations by lazy { data("translations-frieren.json", ListSerializer(Translation.serializer())) }
    private val studios by lazy { Studios.build(translations) }

    @Test fun kindsFollowTheSiteTypes() {
        val kinds = translations.map { it.kind }.toSet()
        assertTrue(TranslationKind.Raw in kinds)
        assertTrue(TranslationKind.VoiceUk in kinds)
        assertTrue(TranslationKind.SubJa in kinds)
        // The site's "other languages" dubs exist in the data but the site doesn't list them, and neither do we.
        assertTrue(TranslationKind.Hidden in kinds)
        assertTrue(studios.none { it.kind == TranslationKind.Hidden })
    }

    @Test fun oneStudioPerAuthorAndKind() {
        assertEquals(studios.size, studios.map { it.key }.distinct().size)
        val libria = studios.first { it.kind == TranslationKind.VoiceRu && it.author == "AniLibria" }
        assertEquals(episodes.size, libria.coverage(episodes))
    }

    @Test fun groupsPutFullStudiosFirst() {
        val voices = Studios.grouped(studios, episodes).getValue(TranslationKind.VoiceRu)
        val counts = voices.map { it.coverage(episodes) }
        assertEquals(counts.sortedDescending(), counts)
        assertEquals(TranslationKind.VoiceRu, Studios.grouped(studios, episodes).keys.first())
    }

    @Test fun choosePrefersPickThenCoverage() {
        val partial = studios.filter { it.kind == TranslationKind.VoiceRu }.minBy { it.coverage(episodes) }
        assertEquals(partial.key, Studios.choose(studios, partial.key, TranslationKind.VoiceRu, emptyList(), episodes, 0)!!.key)
        val auto = Studios.choose(studios, null, TranslationKind.VoiceRu, emptyList(), episodes, 0)!!
        assertEquals(episodes.size, auto.coverage(episodes))
        assertEquals(TranslationKind.SubRu, Studios.choose(studios, null, TranslationKind.SubRu, emptyList(), episodes, 0)!!.kind)
        assertEquals(TranslationKind.Raw, Studios.choose(studios, null, TranslationKind.Raw, emptyList(), episodes, 0)!!.kind)
    }

    @Test fun coverageInWords() {
        val eps = (1..12).map { Episode(id = it.toLong(), episodeFull = "$it серия", episodeInt = it.toDouble()) }
        fun studioWith(vararg have: Int) = Studio(
            "VoiceRu|x", TranslationKind.VoiceRu, "X", emptyList(),
            have.associate { it.toLong() to listOf(Translation(it.toLong(), "X", episodeId = it.toLong())) }, 1080, false, 0,
        )
        assertEquals("все 12 серий", Studios.coverageText(studioWith(*(1..12).toList().toIntArray()), eps))
        assertEquals("11 из 12 · нет 1", Studios.coverageText(studioWith(*(2..12).toList().toIntArray()), eps))
        assertEquals("есть 1–3, 7 из 12", Studios.coverageText(studioWith(1, 2, 3, 7), eps))
        assertEquals("серий пока нет", Studios.coverageText(studioWith(), eps))
    }
}
