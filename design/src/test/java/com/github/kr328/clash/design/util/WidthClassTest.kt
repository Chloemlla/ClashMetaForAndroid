package com.github.kr328.clash.design.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidthClassTest {
    @Test
    fun isDualPaneWidth_trueAtAndAboveThreshold() {
        assertTrue(isDualPaneWidth(DUAL_PANE_MIN_WIDTH_DP))
        assertTrue(isDualPaneWidth(DUAL_PANE_MIN_WIDTH_DP + 1))
        assertTrue(isDualPaneWidth(1280))
    }

    @Test
    fun isDualPaneWidth_falseBelowThreshold() {
        assertFalse(isDualPaneWidth(DUAL_PANE_MIN_WIDTH_DP - 1))
        assertFalse(isDualPaneWidth(360))
        assertFalse(isDualPaneWidth(0))
    }

    @Test
    fun isDualPaneWidth_thresholdMatchesSw600dpQualifier() {
        assertEquals(600, DUAL_PANE_MIN_WIDTH_DP)
    }
}
