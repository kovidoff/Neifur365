package app.neifur

import app.neifur.data.a365.ListStatus
import app.neifur.ui.screens.quickWatchedFor
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickActionsTest {
    @Test
    fun completedFillsTotalEpisodesWhenKnown() {
        assertEquals(24, quickWatchedFor(ListStatus.Completed, currentWatched = 5, total = 24, aired = 24))
    }

    @Test
    fun completedFallsBackToAiredOrMovieOne() {
        assertEquals(12, quickWatchedFor(ListStatus.Completed, currentWatched = 3, total = null, aired = 12))
        assertEquals(1, quickWatchedFor(ListStatus.Completed, currentWatched = 0, total = null, aired = null, isMovie = true))
    }

    @Test
    fun nonCompletedPreservesCurrentProgress() {
        assertEquals(7, quickWatchedFor(ListStatus.Planned, currentWatched = 7, total = 24))
        assertEquals(0, quickWatchedFor(ListStatus.Watching, currentWatched = -2, total = 12))
    }
}
