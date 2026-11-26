package app.neifur

import app.neifur.data.a365.TranslationKind
import app.neifur.downloads.DownloadStatus
import app.neifur.downloads.DownloadedEpisode
import app.neifur.downloads.DownloadsState
import app.neifur.downloads.formatBytes
import app.neifur.downloads.parseContentRangeTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline downloads grouping, size formatting, and synthetic Translation conversion for libmpv. */
class DownloadsTest {
    private fun ep(
        id: Long,
        animeId: String,
        title: String,
        num: Int,
        bytes: Long = 200L shl 20,
        at: Long = 100L,
        kind: TranslationKind = TranslationKind.VoiceRu,
    ) = DownloadedEpisode(
        episodeId = id,
        animeId = animeId,
        animeTitle = title,
        episodeNumber = num,
        episodeLabel = "Серия $num",
        translationId = id * 10,
        translationKind = kind.name,
        studioKey = "${kind.name}|anilibria",
        studioName = "AniLibria",
        height = 1080,
        videoFile = "ep_$id.mp4",
        sizeBytes = bytes,
        downloadedAt = at,
    )

    @Test fun groupsByAnimeOrderedByLatestDownloadAndEpisodesAscending() {
        val st = DownloadsState(
            items = listOf(
                ep(12, "1", "Фрирен", num = 5, at = 300),
                ep(10, "1", "Фрирен", num = 2, at = 100),
                ep(20, "2", "Блич", num = 1, at = 500),
            ),
        )
        val groups = st.byAnime()
        assertEquals(listOf("2", "1"), groups.map { it.animeId })
        assertEquals(listOf(2, 5), groups[1].episodes.map { it.episodeNumber })
        assertEquals(600L shl 20, st.totalBytes)
        assertTrue(st.isDownloaded(12))
        assertFalse(st.isDownloaded(99))
    }

    @Test fun countsQueuedAndRunningSeparatelyFromFailed() {
        val st = DownloadsState(
            active = mapOf(
                1L to DownloadStatus.Running(1L, "1", "A", null, 1, "Серия 1", 0.5f, 50, 100),
                2L to DownloadStatus.Queued(2L, "1", "A", null, 2, "Серия 2"),
                3L to DownloadStatus.Failed(3L, "1", "A", null, 3, "Серия 3", "Нет сети"),
            ),
        )
        assertEquals(2, st.runningCount)
        assertEquals(1L, st.currentRunning?.episodeId)
    }

    @Test fun formatsSizesInRussian() {
        assertEquals("0 МБ", formatBytes(0))
        assertEquals("< 1 МБ", formatBytes(500_000))
        assertEquals("250 МБ", formatBytes(250L shl 20))
        assertEquals("1,5 ГБ", formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test fun downloadedEpisodeBuildsMatchingTranslationKind() {
        for (kind in TranslationKind.shown) {
            val tr = ep(1, "1", "A", 1, kind = kind).toTranslation()
            assertEquals(kind, tr.kind)
            assertTrue(tr.shown)
        }
        assertEquals("AniLibria", ep(1, "1", "A", 1, kind = TranslationKind.VoiceRu).toTranslation().author)
    }

    @Test fun parsesContentRangeTotalForHttpResume() {
        assertEquals(500L, parseContentRangeTotal("bytes 100-199/500"))
        assertEquals(314572800L, parseContentRangeTotal("bytes 1048576-314572799/314572800"))
        assertNull(parseContentRangeTotal("bytes 0-10/*"))
        assertNull(parseContentRangeTotal(null))
        assertNull(parseContentRangeTotal("invalid"))
    }
}
