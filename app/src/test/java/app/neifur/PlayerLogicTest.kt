package app.neifur

import app.neifur.data.WatchEntry
import app.neifur.data.a365.ListEntry
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.ListedTitle
import app.neifur.data.a365.Series
import app.neifur.player.ChapterKind
import app.neifur.player.JUMP_SECONDS
import app.neifur.player.SeekBurst
import app.neifur.player.SleepTimer
import app.neifur.player.canAutoEnterPip
import app.neifur.player.canHoldBoost
import app.neifur.player.canSkipOpening
import app.neifur.player.clampSeekTarget
import app.neifur.player.fixedSurfaceBufferSize
import app.neifur.player.formatSleepBadge
import app.neifur.player.formatSleepCountdown
import app.neifur.player.nextSeekBurst
import app.neifur.player.playerPanelWidth
import app.neifur.player.resolvePlayerWindowSize
import app.neifur.player.shouldAutoAdvance
import app.neifur.player.shouldFinishOnStop
import app.neifur.player.shouldThrottle
import app.neifur.player.speedLabel
import app.neifur.player.time
import androidx.compose.ui.unit.dp
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

    @Test fun seekClampingAndThrottling() {
        assertNull(clampSeekTarget(30.0, 0.0))
        assertNull(clampSeekTarget(30.0, -5.0))
        assertEquals(0.0, clampSeekTarget(-10.0, 1440.0)!!, 0.001)
        assertEquals(25.0, clampSeekTarget(25.0, 1440.0)!!, 0.001)
        assertEquals(1439.5, clampSeekTarget(1500.0, 1440.0)!!, 0.001)

        assertFalse(shouldThrottle(0.0, -1.0, 0.2))
        assertTrue(shouldThrottle(10.1, 10.0, 0.2))
        assertFalse(shouldThrottle(10.25, 10.0, 0.2))
        assertTrue(shouldThrottle(50.3, 50.0, 0.5))
        assertFalse(shouldThrottle(50.6, 50.0, 0.5))
    }

    @Test fun seekBurstAccumulatesOnlySameSideWithinWindow() {
        val first = nextSeekBurst(null, side = 1, stepSec = 5, nowMs = 1_000L)
        assertEquals(SeekBurst(1, 5, 1_000L), first)

        val second = nextSeekBurst(first, side = 1, stepSec = 5, nowMs = 1_600L)
        assertEquals(SeekBurst(1, 10, 1_600L), second)

        val flipped = nextSeekBurst(second, side = -1, stepSec = 5, nowMs = 1_900L)
        assertEquals(SeekBurst(-1, 5, 1_900L), flipped)

        val expired = nextSeekBurst(flipped, side = -1, stepSec = 5, nowMs = 2_800L)
        assertEquals(SeekBurst(-1, 5, 2_800L), expired)
    }

    @Test fun holdBoostPipAndPanelWidthRules() {
        assertTrue(canHoldBoost(holdBoost = true, loaded = true, paused = false, panelOpen = false, currentSpeed = 1.0))
        assertTrue(canHoldBoost(holdBoost = true, loaded = true, paused = false, panelOpen = false, currentSpeed = 1.5))
        assertFalse(canHoldBoost(holdBoost = true, loaded = true, paused = false, panelOpen = false, currentSpeed = 2.0))
        assertFalse(canHoldBoost(holdBoost = true, loaded = true, paused = false, panelOpen = false, currentSpeed = 3.0))
        assertFalse(canHoldBoost(holdBoost = true, loaded = true, paused = true, panelOpen = false, currentSpeed = 1.0))
        assertFalse(canHoldBoost(holdBoost = true, loaded = true, paused = false, panelOpen = true, currentSpeed = 1.0))

        assertTrue(canAutoEnterPip(loaded = true, paused = false, ended = false))
        assertFalse(canAutoEnterPip(loaded = true, paused = false, ended = true))
        assertFalse(canAutoEnterPip(loaded = true, paused = true, ended = false))
        assertFalse(canAutoEnterPip(loaded = false, paused = false, ended = false))

        assertTrue(shouldFinishOnStop(inPip = true, isScreenInteractive = true))
        assertFalse(shouldFinishOnStop(inPip = true, isScreenInteractive = false))
        assertFalse(shouldFinishOnStop(inPip = false, isScreenInteractive = true))

        assertEquals(420.dp, playerPanelWidth(800))
        assertEquals(460.dp, playerPanelWidth(1000))
        assertEquals(336.dp, playerPanelWidth(360))

        assertEquals(2400 to 1080, resolvePlayerWindowSize(1080, 2400, inPip = false))
        assertEquals(2400 to 1080, resolvePlayerWindowSize(2400, 1080, inPip = false))
        assertEquals(400 to 600, resolvePlayerWindowSize(400, 600, inPip = true))
    }

    @Test fun autoAdvanceRunsAtEofEvenWhenKeepOpenPausesPlayback() {
        assertTrue(shouldAutoAdvance(ended = true, hasNext = true, hasFailure = false, cancelled = false, blocked = false, foreground = true, panelOpen = false))
        assertFalse(shouldAutoAdvance(ended = false, hasNext = true, hasFailure = false, cancelled = false, blocked = false, foreground = true, panelOpen = false))
        assertFalse(shouldAutoAdvance(ended = true, hasNext = false, hasFailure = false, cancelled = false, blocked = false, foreground = true, panelOpen = false))
        assertFalse(shouldAutoAdvance(ended = true, hasNext = true, hasFailure = true, cancelled = false, blocked = false, foreground = true, panelOpen = false))
        assertFalse(shouldAutoAdvance(ended = true, hasNext = true, hasFailure = false, cancelled = true, blocked = false, foreground = true, panelOpen = false))
        assertFalse(shouldAutoAdvance(ended = true, hasNext = true, hasFailure = false, cancelled = false, blocked = true, foreground = true, panelOpen = false))
        assertFalse(shouldAutoAdvance(ended = true, hasNext = true, hasFailure = false, cancelled = false, blocked = false, foreground = false, panelOpen = false))
        assertFalse(shouldAutoAdvance(ended = true, hasNext = true, hasFailure = false, cancelled = false, blocked = false, foreground = true, panelOpen = true))
    }

    @Test fun fixedSurfaceBufferSizeNormalizesQualitySwitchesByAspect() {
        assertEquals(1920 to 1080, fixedSurfaceBufferSize(1920, 1080))
        assertEquals(1920 to 1080, fixedSurfaceBufferSize(1280, 720))
        assertEquals(1920 to 1080, fixedSurfaceBufferSize(854, 480))
        assertEquals(1920 to 1080, fixedSurfaceBufferSize(640, 360))
        assertEquals(1920 to 1080, fixedSurfaceBufferSize(3840, 2160))
        assertEquals(1920 to 1080, fixedSurfaceBufferSize(0, 0))

        assertEquals(1440 to 1080, fixedSurfaceBufferSize(1440, 1080))
        assertEquals(1440 to 1080, fixedSurfaceBufferSize(960, 720))
        assertEquals(1440 to 1080, fixedSurfaceBufferSize(640, 480))

        assertEquals(2560 to 1080, fixedSurfaceBufferSize(2560, 1080))
        val (cappedW, cappedH) = fixedSurfaceBufferSize(3840, 1600)
        assertEquals(2560, cappedW)
        assertEquals(0, cappedH % 2)
    }
}
