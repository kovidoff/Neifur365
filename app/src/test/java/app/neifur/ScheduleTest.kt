package app.neifur

import app.neifur.data.a365.Episode
import app.neifur.data.a365.ListEntry
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.ListedTitle
import app.neifur.data.a365.Series
import app.neifur.data.shikimori.Anime
import app.neifur.downloads.DownloadStatus
import app.neifur.downloads.DownloadedEpisode
import app.neifur.downloads.DownloadsState
import app.neifur.ui.screens.buildWeeklySchedule
import app.neifur.ui.screens.formatRelativeAir
import app.neifur.ui.screens.nextBatchToDownload
import app.neifur.ui.screens.shortWeekday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Ongoing weekly schedule grouping/countdowns and batch offline download selection. */
class ScheduleTest {
    private val msk = ZoneId.of("Europe/Moscow")

    @Test fun groupsWithinSevenDayWindowAndMatchesUserLists() {
        val today = LocalDate.of(2026, 11, 22)
        val a1 = Anime(id = "52991", name = "Frieren", russian = "Фрирен", episodes = 28, episodesAired = 7, nextEpisodeAt = "2026-11-22T18:30:00+03:00")
        val a2 = Anime(id = "100", name = "Early", russian = "Ранний", episodes = 12, episodesAired = 3, nextEpisodeAt = "2026-11-22T12:00:00+03:00")
        val a3 = Anime(id = "200", name = "NextDay", russian = "Завтрашний", episodes = 12, episodesAired = 5, nextEpisodeAt = "2026-11-23T20:00:00+03:00")
        val outOfRange = Anime(id = "300", name = "Delayed", nextEpisodeAt = "2026-12-05T12:00:00+03:00")

        val listed = mapOf(
            52991L to ListedTitle(
                entry = ListEntry(30414L, "Фрирен", 7, 28, null, ListStatus.Watching),
                series = Series(30414L, myAnimeListId = 52991L),
            )
        )
        val week = buildWeeklySchedule(listOf(a1, a2, a3, outOfRange), listed, msk, today)
        assertEquals(7, week.size)
        // Same day ordered chronologically: 12:00 before 18:30.
        assertEquals(listOf("100", "52991"), week[today]!!.map { it.malId })
        val frieren = week[today]!![1]
        assertEquals(30414L, frieren.seriesId)
        assertEquals(8, frieren.nextEpisode)
        assertTrue(frieren.isMine)
        assertFalse(week[today]!![0].isMine)
        assertEquals(listOf("200"), week[today.plusDays(1)]!!.map { it.malId })
    }

    @Test fun formatsRelativeCountdownsAndWeekdays() {
        val now = ZonedDateTime.of(2026, 11, 22, 15, 0, 0, 0, msk)
        assertEquals("вышла 30 мин назад" to true, formatRelativeAir(now.minusMinutes(30), now))
        assertEquals("вышла 2 ч назад" to true, formatRelativeAir(now.minusHours(2), now))
        assertEquals("через 45 мин" to false, formatRelativeAir(now.plusMinutes(45), now))
        assertEquals("через 2 ч 15 мин" to false, formatRelativeAir(now.plusMinutes(135), now))
        assertEquals("через 3 ч" to false, formatRelativeAir(now.plusHours(3), now))
        assertEquals("Пн", shortWeekday(DayOfWeek.MONDAY))
        assertEquals("Вс", shortWeekday(DayOfWeek.SUNDAY))
    }

    @Test fun batchDownloadSkipsSavedAndActiveEpisodes() {
        val eps = (1..6).map { Episode(id = it.toLong(), episodeFull = "$it серия", episodeInt = it.toDouble()) }
        val saved = DownloadedEpisode(
            episodeId = 3L, animeId = "1", animeTitle = "T", episodeNumber = 3, episodeLabel = "Серия 3",
            translationId = 1, translationKind = "VoiceRu", studioKey = "VoiceRu|x", studioName = "X",
            height = 1080, videoFile = "ep_3.mp4", sizeBytes = 100L, downloadedAt = 1L,
        )
        val downloads = DownloadsState(
            items = listOf(saved),
            active = mapOf(
                4L to DownloadStatus.Queued(4L, "1", "T", null, 4, "Серия 4"),
                5L to DownloadStatus.Failed(5L, "1", "T", null, 5, "Серия 5", "err"),
            ),
        )
        // Starting at index 1 (ep 2): skips ep 3 (downloaded) and ep 4 (queued), picks ep 2, ep 5 (failed -> retryable), ep 6.
        assertEquals(listOf(2L, 5L, 6L), nextBatchToDownload(eps, startIndex = 1, downloads = downloads, count = 3).map { it.id })
    }
}
