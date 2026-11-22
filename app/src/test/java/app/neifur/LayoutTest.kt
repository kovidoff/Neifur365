package app.neifur

import app.neifur.ui.ScreenSize
import app.neifur.ui.layoutFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    @Test
    fun phoneKeepsClassicThreeColumnLayout() {
        val phone = layoutFor(411, 915)
        assertEquals(ScreenSize.Compact, phone.size)
        assertFalse(phone.isTablet)
        assertEquals(3, phone.posterColumns)
        assertEquals(1, phone.wideColumns)
        assertEquals(3, phone.wideSpan)
    }

    @Test
    fun tabletPortraitUsesFourColumns() {
        // OnePlus Pad 2 in portrait (~706x1000 dp)
        val padPortrait = layoutFor(706, 1000)
        assertEquals(ScreenSize.Medium, padPortrait.size)
        assertTrue(padPortrait.isTablet)
        assertFalse(padPortrait.isExpanded)
        assertEquals(4, padPortrait.posterColumns)
        assertEquals(1, padPortrait.wideColumns)
        assertEquals(4, padPortrait.wideSpan)
    }

    @Test
    fun tabletLandscapeUsesSixPostersAndTwoWideColumns() {
        // OnePlus Pad 2 in landscape (~1000x706 dp)
        val padLandscape = layoutFor(1000, 706)
        assertEquals(ScreenSize.Expanded, padLandscape.size)
        assertTrue(padLandscape.isExpanded)
        assertTrue(padLandscape.isLandscape)
        assertEquals(6, padLandscape.posterColumns)
        assertEquals(2, padLandscape.wideColumns)
        assertEquals(3, padLandscape.wideSpan)
    }

    @Test
    fun ultraWideTabletUsesEightPostersAndTwoWideColumns() {
        val ultra = layoutFor(1280, 800)
        assertEquals(ScreenSize.Expanded, ultra.size)
        assertEquals(8, ultra.posterColumns)
        assertEquals(2, ultra.wideColumns)
        assertEquals(4, ultra.wideSpan)
    }
}
