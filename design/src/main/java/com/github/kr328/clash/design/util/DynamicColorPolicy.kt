package com.github.kr328.clash.design.util

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import com.github.kr328.clash.design.model.DarkMode

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
fun isDynamicColorAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

fun isDynamicColorAvailable(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
fun shouldUseDynamicColors(enabled: Boolean): Boolean =
    enabled && isDynamicColorAvailable()

fun shouldUseDynamicColors(enabled: Boolean, sdkInt: Int): Boolean =
    enabled && isDynamicColorAvailable(sdkInt)

fun shouldUseDarkIllustrationColors(darkMode: DarkMode, systemDark: Boolean): Boolean =
    when (darkMode) {
        DarkMode.Auto -> systemDark
        DarkMode.ForceLight -> false
        DarkMode.ForceDark -> true
    }
