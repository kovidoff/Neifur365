package app.neifur

import app.neifur.data.WatchEntry
import app.neifur.data.a365.ListEntry
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.ListedTitle
import app.neifur.data.a365.Series
import app.neifur.player.ChapterKind
import app.neifur.player.JUMP_SECONDS
import app.neifur.player.SleepTimer
import app.neifur.player.canSkipOpening
import app.neifur.player.formatSleepBadge
import app.neifur.player.formatSleepCountdown
import app.neifur.player.speedLabel
import app.neifur.player.time
import app.neifur.ui.screens.continueLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure player helpers: +85 s skip availability, chapter classification, time/speed labels and continue-watching status. */
class PlayerLogicTest {
    @Test fun openingSkipAvailableForLoadedEpisodes() {
        assertEquals(85, JUMP_SECONDS)
        assertTrue(canSkipOpening(loaded = true, ended = false, duration = 1440.0))
        assertTrue(canSkipOpening(loaded = true, ended = false, duration = 240.0))
        assertFalse(canSkipOpening(loaded = false, ended = false, duration = 1440.0))
        assertFalse(canSkipOpening(loaded = true, ended = true, duration = 1440.0))
        assertFalse(canSkipOpening(loaded = true, ended = false, duration = 90.0))
    }

    @Test fun chapterKindsRecogniseCommonNames() {
        assertEquals(ChapterKind.Opening, ChapterKind.of("OP1"))
        assertEquals(ChapterKind.Opening, ChapterKind.of("Opening"))
        assertEquals(ChapterKind.Opening, ChapterKind.of("オープニング"))
        assertEquals(ChapterKind.Ending, ChapterKind.of("ED"))
        assertEquals(ChapterKind.Ending, ChapterKind.of("Credits"))
        assertEquals(ChapterKind.Preview, ChapterKind.of("Next Episode"))
        assertEquals(ChapterKind.Other, ChapterKind.of("Part A"))
    }

    @Test fun timeAndSpeedFormatting() {
        assertEquals("0:00", time(0.0))
        assertEquals("1:25", time(85.0))
        assertEquals("1:02:05", time(3725.0))
        assertEquals("1×", speedLabel(1.0))
        assertEquals("2×", speedLabel(2.0))
        assertEquals("1.25×", speedLabel(1.25))
        assertEquals("1.5×", speedLabel(1.5))
    }

    @Test fun continueLinePrefersFurtherSiteProgress() {
        val local = WatchEntry("1", "Test", episode = 3, positionSec = 600.0, durationSec = 1440.0)
        assertEquals("Серия 3 · осталось 14 мин", continueLine(local, null).first)

        val ahead = ListedTitle(ListEntry(1, "Test", watched = 5, total = 12, score = null, status = ListStatus.Watching), Series(1, numberOfEpisodes = 12))
        assertEquals("Дальше серия 6" to 0f, continueLine(local, ahead))

        val finished = ListedTitle(ListEntry(1, "Test", watched = 12, total = 12, score = null, status = ListStatus.Completed), Series(1, numberOfEpisodes = 12))
        assertEquals("Все серии просмотрены" to 1f, continueLine(local, finished))
    }

    @Test fun sleepTimerBadgesAndCountdowns() {
        val until = SleepTimer.Until(100_000L)
        assertNull(formatSleepBadge(SleepTimer.Off, 0L))
        assertEquals("Конец", formatSleepBadge(SleepTimer.EndOfEpisode, 0L))
        assertEquals("0:42", formatSleepBadge(until, 41_500L))
        assertEquals("15 м", formatSleepBadge(until, 15 * 60_000L))
        assertEquals("1 ч", formatSleepBadge(until, 60 * 60_000L))
        assertEquals("1 ч 30 м", formatSleepBadge(until, 90 * 60_000L))

        assertEquals("0:00", formatSleepCountdown(0L))
        assertEquals("0:42", formatSleepCountdown(41_500L))
        assertEquals("14:32", formatSleepCountdown((14 * 60 + 32) * 1000L))
        assertEquals("1:14:32", formatSleepCountdown((74 * 60 + 32) * 1000L))
    }
}
