package app.neifur

import app.neifur.data.SettingsBackup
import app.neifur.data.WatchEntry
import app.neifur.data.a365.ListEntry
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.ListedTitle
import app.neifur.data.a365.Series
import app.neifur.data.aniskip.parseAndFilterSkipTimes
import app.neifur.data.aniskip.skipSegmentsToChapters
import app.neifur.data.decodeSettingsBackup
import app.neifur.data.encodeSettingsBackup
import app.neifur.data.shikimori.IncDate
import app.neifur.data.shikimori.RelatedRef
import app.neifur.data.shikimori.sortFranchiseEntries
import app.neifur.player.ChapterKind
import app.neifur.ui.screens.SheetDragController
import app.neifur.ui.screens.SheetReleaseAction
import app.neifur.ui.screens.decideRelease
import app.neifur.ui.screens.filterContinueWatching
import app.neifur.ui.screens.isListAtTop
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Wave3LogicTest {

    @Test
    fun aniSkipAcceptsMatchingDurationAndRejectsStudioBumperShift() {
        val payload = """
            {
              "found": true,
              "results": [
                {"interval":{"startTime":103.375,"endTime":192.917},"skipType":"op","episodeLength":1420.0},
                {"interval":{"startTime":1325.0,"endTime":1414.0},"skipType":"ed","episodeLength":1420.0}
              ]
            }
        """.trimIndent()

        // Exact length & minor container variance (+1.2 s) are accepted.
        val exact = parseAndFilterSkipTimes(payload, videoDuration = 1421.2)
        assertEquals(2, exact.size)
        assertEquals(ChapterKind.Opening, exact[0].kind)
        assertEquals(ChapterKind.Ending, exact[1].kind)

        // Dub studio prepended a 6.5 s intro -> total duration is 1426.5 s -> strictly rejected.
        val shifted = parseAndFilterSkipTimes(payload, videoDuration = 1426.5)
        assertTrue(shifted.isEmpty())
    }

    @Test
    fun aniSkipMapsRecapSeparatelyAndBuildsContiguousChapters() {
        val payload = """
            {
              "found": true,
              "results": [
                {"interval":{"startTime":5.0,"endTime":65.0},"skipType":"recap","episodeLength":1440.0},
                {"interval":{"startTime":80.0,"endTime":170.0},"skipType":"mixed-op","episodeLength":1440.0},
                {"interval":{"startTime":82.0,"endTime":172.0},"skipType":"op","episodeLength":1440.0},
                {"interval":{"startTime":10.0,"endTime":600.0},"skipType":"ed","episodeLength":1440.0}
              ]
            }
        """.trimIndent()

        val segs = parseAndFilterSkipTimes(payload, videoDuration = 1440.0)
        // Vandalized 590s ED is dropped; pure "op" beats "mixed-op"; recap gets ChapterKind.Recap.
        assertEquals(2, segs.size)
        assertEquals(ChapterKind.Recap, segs[0].kind)
        assertEquals(ChapterKind.Opening, segs[1].kind)
        assertEquals(82.0, segs[1].start, 0.01)

        val chapters = skipSegmentsToChapters(segs, duration = 1440.0)
        assertTrue(chapters.first().start == 0.0 && chapters.first().kind == ChapterKind.Other)
        assertTrue(chapters.any { it.kind == ChapterKind.Recap && it.start == 5.0 })
        assertTrue(chapters.any { it.kind == ChapterKind.Opening && it.start == 82.0 })
        assertTrue(chapters.last().kind == ChapterKind.Other && chapters.last().start == 172.0)
    }

    @Test
    fun franchiseSortsChronologicallyAndExcludesMusicAndAds() {
        val raw = listOf(
            RelatedRef(id = "63816", name = "S3", kind = "tv", airedOn = IncDate(2027, 10, null)),
            RelatedRef(id = "58313", name = "MV", kind = "music", airedOn = IncDate(2024, 3, 5)),
            RelatedRef(id = "60462", name = "CM", kind = "cm", airedOn = IncDate(2024, 12, 4)),
            RelatedRef(id = "59978", name = "S2", kind = "tv", airedOn = IncDate(2026, 1, 16)),
            RelatedRef(id = "52991", name = "S1", kind = "tv", airedOn = IncDate(2023, 9, 29)),
            RelatedRef(id = "56885", name = "Mini", kind = "ona", airedOn = IncDate(2023, 10, 11)),
        )
        val sorted = sortFranchiseEntries(raw)
        assertEquals(listOf("52991", "56885", "59978", "63816"), sorted.map { it.id })
    }

    @Test
    fun continueWatchingHidesCompletedAndDroppedButKeepsActiveAndOngoing() {
        val history = listOf(
            WatchEntry("1", "Completed Show", episode = 12, positionSec = 1400.0, durationSec = 1440.0),
            WatchEntry("2", "Finished Non-Airing", episode = 24, positionSec = 1400.0, durationSec = 1440.0),
            WatchEntry("3", "Ongoing Show", episode = 10, positionSec = 1400.0, durationSec = 1440.0),
            WatchEntry("4", "Midway Show", episode = 5, positionSec = 600.0, durationSec = 1440.0),
            WatchEntry("5", "Unlisted Local", episode = 2, positionSec = 300.0, durationSec = 1440.0),
            WatchEntry("6", "Dropped Show", episode = 3, positionSec = 500.0, durationSec = 1440.0),
        )
        val listed = mapOf(
            1L to ListedTitle(ListEntry(1L, "Completed Show", 12, 12, 9, ListStatus.Completed), Series(id = 1L, numberOfEpisodes = 12, isAiring = 0)),
            2L to ListedTitle(ListEntry(2L, "Finished Non-Airing", 24, 24, null, ListStatus.Watching), Series(id = 2L, numberOfEpisodes = 24, isAiring = 0)),
            3L to ListedTitle(ListEntry(3L, "Ongoing Show", 10, 10, null, ListStatus.Watching), Series(id = 3L, numberOfEpisodes = 10, isAiring = 1)),
            4L to ListedTitle(ListEntry(4L, "Midway Show", 4, 12, null, ListStatus.Watching), Series(id = 4L, numberOfEpisodes = 12, isAiring = 0)),
            6L to ListedTitle(ListEntry(6L, "Dropped Show", 3, 12, null, ListStatus.Dropped), Series(id = 6L, numberOfEpisodes = 12, isAiring = 0)),
        )

        val kept = filterContinueWatching(history, listed).map { it.animeId }
        assertEquals(listOf("3", "4", "5"), kept)
    }

    @Test
    fun settingsBackupRoundTripsAndRejectsForeignJson() {
        val original = SettingsBackup(
            exportedAt = 123456L,
            preferredKind = "SubRu",
            favouriteStudios = listOf("SubRu|anilibria" to "AniLibria"),
            translationPicks = mapOf("100" to "VoiceRu|onwave"),
            quality = 720,
            subScale = 1.25,
            seekStep = 5,
            aniSkip = true,
            normalizeAudio = true,
            wifiOnlyDownloads = true,
        )
        val encoded = encodeSettingsBackup(original)
        val decoded = decodeSettingsBackup(encoded)
        assertEquals(original, decoded)

        val rejected = runCatching { decodeSettingsBackup("""{"app":"OtherApp","version":1}""") }.isFailure
        assertTrue(rejected)
    }

    @Test
    fun listAtTopUsesSmallToleranceOnFirstItemOnly() {
        assertTrue(isListAtTop(0, 0))
        assertTrue(isListAtTop(0, 8))
        assertFalse(isListAtTop(0, 9))
        assertFalse(isListAtTop(1, 0))
    }

    @Test
    fun sheetDragControllerOnlyDragsWhenGestureStartsAtTopAndReleasesCleanly() = runBlocking {
        var atTop = false
        var offset = 0f
        var release: SheetReleaseAction? = null
        val ctrl = SheetDragController(
            isListAtTop = { atTop },
            getOffset = { offset },
            setOffset = { offset = it },
            getSheetHeight = { 1000f },
            onRelease = { release = it },
        )

        // 1. Touch down mid-list -> scroll down + bottom overscroll -> sheet never moves.
        ctrl.onNewTouchDown()
        assertEquals(Offset.Zero, ctrl.onPreScroll(Offset(0f, -120f), NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, ctrl.onPostScroll(Offset(0f, -20f), Offset(0f, -80f), NestedScrollSource.UserInput))
        assertEquals(Velocity.Zero, ctrl.onPostFling(Velocity(0f, -400f), Velocity(0f, -600f)))
        assertEquals(0f, offset, 0.01f)
        assertEquals(null, release)

        // 2. Touch down mid-list, drag UP hitting top -> atTop flips true mid-gesture, leftover + fling still ignored.
        ctrl.onNewTouchDown()
        assertEquals(Offset.Zero, ctrl.onPreScroll(Offset(0f, 90f), NestedScrollSource.UserInput))
        atTop = true
        assertEquals(Offset.Zero, ctrl.onPreScroll(Offset(0f, 60f), NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, ctrl.onPostScroll(Offset(0f, 30f), Offset(0f, 60f), NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, ctrl.onPostScroll(Offset.Zero, Offset(0f, 50f), NestedScrollSource.SideEffect))
        assertEquals(Velocity.Zero, ctrl.onPostFling(Velocity(0f, 200f), Velocity(0f, 700f)))
        assertEquals(0f, offset, 0.01f)
        assertEquals(null, release)

        // 3. Touch down cleanly at top, scroll down into list first (available.y < 0f), then reverse up -> still locked.
        ctrl.onNewTouchDown()
        assertEquals(Offset.Zero, ctrl.onPreScroll(Offset(0f, -40f), NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, ctrl.onPreScroll(Offset(0f, 90f), NestedScrollSource.UserInput))
        assertEquals(0f, offset, 0.01f)
        assertEquals(null, release)

        // 4. Touch down cleanly at top -> onPreScroll immediately intercepts downward pull before OverscrollEffect,
        //    follows finger symmetrically up and down, and commits SnapOpen below threshold on release.
        ctrl.onNewTouchDown()
        assertEquals(Offset(0f, 120f), ctrl.onPreScroll(Offset(0f, 120f), NestedScrollSource.UserInput))
        assertEquals(120f, offset, 0.01f)
        assertEquals(Offset(0f, -30f), ctrl.onPreScroll(Offset(0f, -30f), NestedScrollSource.UserInput))
        assertEquals(90f, offset, 0.01f)
        ctrl.onPreFling(Velocity(0f, 100f))
        assertEquals(SheetReleaseAction.SnapOpen, release)

        // 5. Threshold & fast velocity decide Dismiss.
        assertEquals(SheetReleaseAction.None, decideRelease(0f, 2000f, 1000f))
        assertEquals(SheetReleaseAction.SnapOpen, decideRelease(150f, 200f, 1000f))
        assertEquals(SheetReleaseAction.Dismiss, decideRelease(260f, 100f, 1000f))
        assertEquals(SheetReleaseAction.Dismiss, decideRelease(60f, 1200f, 1000f))
    }
}
