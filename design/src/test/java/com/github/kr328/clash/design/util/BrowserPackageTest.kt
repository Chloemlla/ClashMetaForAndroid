package com.github.kr328.clash.design.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPackageTest {
    @Test
    fun isKnownBrowserPackage_matchesWellKnownBrowsers() {
        assertTrue(isKnownBrowserPackage("com.android.chrome"))
        assertTrue(isKnownBrowserPackage("org.mozilla.firefox"))
        assertTrue(isKnownBrowserPackage("com.brave.browser"))
        assertTrue(isKnownBrowserPackage("com.duckduckgo.mobile.android"))
    }

    @Test
    fun isKnownBrowserPackage_rejectsUnrelatedPackages() {
        assertFalse(isKnownBrowserPackage("com.example.notabrowser"))
        assertFalse(isKnownBrowserPackage(""))
        assertFalse(isKnownBrowserPackage("com.chloemlla.piliplus"))
    }
}
