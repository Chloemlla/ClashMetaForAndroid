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

    // B-24 / B-99: cutting between a surrogate pair renders flag emoji as tofu. The truncate
    // boundary must never leave a dangling high surrogate (its low partner cut off).
    private val flagEmoji = "🇨🇳" // CN flag, 4 UTF-16 units / 2 pairs

    @Test
    fun truncate_boundaryOnHighSurrogateBacksOff() {
        // text = "abc" + flag; budget 5 keeps 4 chars, and index 3 is the high surrogate of the
        // flag. Without B-24 the result would be "abc\uD83C…" (dangling high surrogate).
        val cut = WidgetFormat.truncate("abc$flagEmoji", 5)
        assertEquals("abc…", cut)
    }

    @Test
    fun truncate_keptPrefixNeverEndsInDanglingHighSurrogate() {
        // Boundary lands just before the second regional indicator; the kept prefix ends inside a
        // complete pair, which is valid UTF-16 (not tofu).
        val cut = WidgetFormat.truncate("abc$flagEmoji", 6)
        assertEquals("abc🇨…", cut)
    }

    @Test
    fun truncate_fullPairPlusEllipsisKeepsWholePair() {
        assertEquals("$flagEmoji…", WidgetFormat.truncate("$flagEmoji" + "tail", flagEmoji.length + 1))
    }

    @Test
    fun truncate_shortEmojiTextStaysUnchanged() {
        assertEquals(flagEmoji, WidgetFormat.truncate(flagEmoji, flagEmoji.length + 1))
    }

    @Test
    fun truncate_budgetSmallerThanPairFallsBackToEllipsis() {
        // A 4-unit flag cannot fit in a 2-char budget (1 kept + ellipsis); fall back to "…".
        assertEquals("…", WidgetFormat.truncate("$flagEmoji" + "x", 2))
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
