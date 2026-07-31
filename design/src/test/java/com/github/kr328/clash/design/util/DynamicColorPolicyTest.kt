package com.github.kr328.clash.design.util

import com.github.kr328.clash.design.model.DarkMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicColorPolicyTest {
    @Test
    fun requiresOptInAndAndroid12() {
        assertFalse(shouldUseDynamicColors(enabled = false, sdkInt = 35))
        assertFalse(shouldUseDynamicColors(enabled = true, sdkInt = 30))
        assertTrue(shouldUseDynamicColors(enabled = true, sdkInt = 31))
    }

    @Test
    fun capabilityStartsAtAndroid12() {
        assertFalse(isDynamicColorAvailable(sdkInt = 30))
        assertTrue(isDynamicColorAvailable(sdkInt = 31))
    }

    @Test
    fun illustrationDarkModeFollowsAppPreference() {
        assertFalse(shouldUseDarkIllustrationColors(DarkMode.Auto, systemDark = false))
        assertTrue(shouldUseDarkIllustrationColors(DarkMode.Auto, systemDark = true))
        assertFalse(shouldUseDarkIllustrationColors(DarkMode.ForceLight, systemDark = true))
        assertTrue(shouldUseDarkIllustrationColors(DarkMode.ForceDark, systemDark = false))
    }
}
