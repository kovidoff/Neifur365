package app.neifur

import app.neifur.data.WatchEntry
import app.neifur.data.a365.ListEntry
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.ListedTitle
import app.neifur.data.a365.Series
import app.neifur.player.Chapter
import app.neifur.player.ChapterKind
import app.neifur.player.JUMP_WINDOW
import app.neifur.player.shouldOfferOpeningJump
import app.neifur.player.speedLabel
import app.neifur.player.time
import app.neifur.ui.screens.continueLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure player helpers: opening skip button, chapter classification, time/speed labels and continue-watching status. */
class PlayerLogicTest {
    @Test fun openingJumpShowsInFirstFiveMinutesWithoutOpeningChapter() {
        assertTrue(shouldOfferOpeningJump(false, controlsShown = true, ended = false, duration = 1440.0, position = 45.0, chapters = emptyList()))
        // Chapters without an Opening mark (e.g. only Part A / Ending) still allow the +85 s button.
        val noOp = listOf(Chapter(0.0, "Part A"), Chapter(1300.0, "Ending"))
        assertTrue(shouldOfferOpeningJump(false, controlsShown = true, ended = false, duration = 1440.0, position = 45.0, chapters = noOp))
    }

    @Test fun openingJumpStaysHiddenWhenInappropriate() {
        val withOp = listOf(Chapter(0.0, "Prologue"), Chapter(90.0, "Opening"), Chapter(180.0, "Part A"))
        // File already has an Opening chapter: wait for the exact chapter skip button instead of guessing +85 s.
        assertFalse(shouldOfferOpeningJump(false, controlsShown = true, ended = false, duration = 1440.0, position = 20.0, chapters = withOp))
        // Chapter skip already shown, controls hidden, ended, short video, or past the 5-minute window.
        assertFalse(shouldOfferOpeningJump(true, controlsShown = true, ended = false, duration = 1440.0, position = 20.0, chapters = emptyList()))
        assertFalse(shouldOfferOpeningJump(false, controlsShown = false, ended = false, duration = 1440.0, position = 20.0, chapters = emptyList()))
        assertFalse(shouldOfferOpeningJump(false, controlsShown = true, ended = true, duration = 1440.0, position = 20.0, chapters = emptyList()))
        assertFalse(shouldOfferOpeningJump(false, controlsShown = true, ended = false, duration = 300.0, position = 20.0, chapters = emptyList()))
        assertFalse(shouldOfferOpeningJump(false, controlsShown = true, ended = false, duration = 1440.0, position = JUMP_WINDOW, chapters = emptyList()))
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
}
