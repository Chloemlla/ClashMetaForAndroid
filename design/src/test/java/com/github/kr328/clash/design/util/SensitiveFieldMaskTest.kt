package com.github.kr328.clash.design.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveFieldMaskTest {
    @Test
    fun maskSecret_blankReturnsEmpty() {
        assertEquals("", SensitiveFieldMask.maskSecret(null))
        assertEquals("", SensitiveFieldMask.maskSecret(""))
    }

    @Test
    fun maskSecret_neverLeaksOriginalCharacters() {
        val secret = "AGE-SECRET-KEY-1QYQSZQGPQYQSZQGPQYQSZQGPQYQSZQGPQYQSZQGPQYQSZQGPQYQQ99999"
        val masked = SensitiveFieldMask.maskSecret(secret)

        assertTrue(masked.isNotEmpty())
        assertTrue(masked.all { it == '•' })
        assertEquals(false, masked.contains("AGE-SECRET-KEY"))
    }

    @Test
    fun maskUrl_blankReturnsEmpty() {
        assertEquals("", SensitiveFieldMask.maskUrl(null))
        assertEquals("", SensitiveFieldMask.maskUrl(""))
    }

    @Test
    fun maskUrl_keepsSchemeAndHostHidesPath() {
        val masked = SensitiveFieldMask.maskUrl("https://example.com/sub?token=abcdef123456")

        assertTrue(masked.startsWith("https://example.com/"))
        assertEquals(false, masked.contains("token"))
        assertEquals(false, masked.contains("abcdef123456"))
    }

    @Test
    fun maskUrl_withoutSchemeFallsBackToFullMask() {
        val masked = SensitiveFieldMask.maskUrl("example.com/secret-path")

        assertTrue(masked.isNotEmpty())
        assertEquals(false, masked.contains("secret-path"))
    }

    @Test
    fun display_revealedReturnsOriginal() {
        val value = "https://example.com/sub?token=abcdef123456"
        assertEquals(value, SensitiveFieldMask.display(value, revealed = true, urlStyle = true))
    }

    @Test
    fun display_hiddenNeverReturnsOriginal() {
        val value = "https://example.com/sub?token=abcdef123456"
        val displayed = SensitiveFieldMask.display(value, revealed = false, urlStyle = true)

        assertEquals(false, displayed == value)
        assertEquals(false, displayed.contains("token"))
    }
}
