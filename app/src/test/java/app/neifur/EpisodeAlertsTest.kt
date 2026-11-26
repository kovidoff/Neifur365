package app.neifur

import app.neifur.alerts.shouldCheckSeries
import app.neifur.data.a365.ListEntry
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.Series
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure eligibility rules for checking episode alerts on airing and recently completed titles. */
class EpisodeAlertsTest {
    @Test fun checksAiringShowsEvenBeforeSeenMarkExists() {
        val entry = ListEntry(1L, "Frieren", watched = 5, total = 28, score = null, status = ListStatus.Watching)
        val airingCard = Series(id = 1L, isAiring = 1, numberOfEpisodes = 28)
        assertTrue(shouldCheckSeries(entry, airingCard, knownSeen = null))
        assertTrue(shouldCheckSeries(entry, airingCard, knownSeen = 28))
    }

    @Test fun keepsCheckingRecentlyFinishedOngoingUntilFinalEpisodeNotified() {
        val entry = ListEntry(1L, "Show", watched = 10, total = 12, score = null, status = ListStatus.Watching)
        val finishedCard = Series(id = 1L, isAiring = 0, numberOfEpisodes = 12)

        assertTrue(shouldCheckSeries(entry, finishedCard, knownSeen = 11))
        assertFalse(shouldCheckSeries(entry, finishedCard, knownSeen = 12))
        assertFalse(shouldCheckSeries(entry.copy(watched = 12), finishedCard, knownSeen = 11))
        assertFalse(shouldCheckSeries(entry, finishedCard, knownSeen = null))
    }
}
