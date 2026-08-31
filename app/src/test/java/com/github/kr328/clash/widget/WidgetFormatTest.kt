package com.github.kr328.clash.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WidgetFormatTest {
    @Test
    fun truncate_keepsShortText() {
        assertEquals("hello", WidgetFormat.truncate("hello", 10))
    }

    @Test
    fun truncate_appendsEllipsis() {
        assertEquals("hel…", WidgetFormat.truncate("hello-world", 4))
    }

    @Test
    fun truncate_singleCharBudgetIsEllipsis() {
        assertEquals("…", WidgetFormat.truncate("abc", 1))
    }

    @Test
    fun compactRate_formatsUnits() {
        assertEquals("800B", WidgetFormat.compactRate(800))
        assertEquals("1.5K", WidgetFormat.compactRate(1536))
        assertEquals("2M", WidgetFormat.compactRate(2L * 1024 * 1024))
    }

    @Test
    fun ratesLine_includesBothDirections() {
        assertEquals("↑1K/s  ↓2K/s", WidgetFormat.ratesLine(1024, 2048))
    }

    @Test
    fun model_equalityDrivesRedrawSkip() {
        val a = WidgetUiModel(
            running = true,
            profileName = "p",
            selectedNode = "n",
            mode = "Rule",
            ratesText = "↑1K/s  ↓2K/s",
            hasRates = true,
        )

        assertEquals(a, a.copy())
        assertNotEquals(a, a.copy(running = false))
        assertNotEquals(a, a.copy(selectedNode = "other"))
        assertNotEquals(a, a.copy(mode = "Global"))
    }
}
