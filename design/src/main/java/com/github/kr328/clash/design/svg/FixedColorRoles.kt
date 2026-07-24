package com.github.kr328.clash.design.svg

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Simplified Material "fixed" accent roles used by undraw dynamic-color vectors.
 * Derived from light/dark [ColorScheme] containers so illustrations stay readable
 * without Seal's Monet tonal palette stack.
 */
@Immutable
data class FixedColorRoles(
    val primaryFixed: Color,
    val primaryFixedDim: Color,
    val onPrimaryFixed: Color,
    val onPrimaryFixedVariant: Color,
    val secondaryFixed: Color,
    val secondaryFixedDim: Color,
    val onSecondaryFixed: Color,
    val onSecondaryFixedVariant: Color,
    val tertiaryFixed: Color,
    val tertiaryFixedDim: Color,
    val onTertiaryFixed: Color,
    val onTertiaryFixedVariant: Color,
) {
    companion object {
        @Stable
        fun fromColorSchemes(
            lightColors: ColorScheme,
            darkColors: ColorScheme,
        ): FixedColorRoles {
            return FixedColorRoles(
                primaryFixed = lightColors.primaryContainer,
                onPrimaryFixed = lightColors.onPrimaryContainer,
                onPrimaryFixedVariant = darkColors.primaryContainer,
                secondaryFixed = lightColors.secondaryContainer,
                onSecondaryFixed = lightColors.onSecondaryContainer,
                onSecondaryFixedVariant = darkColors.secondaryContainer,
                tertiaryFixed = lightColors.tertiaryContainer,
                onTertiaryFixed = lightColors.onTertiaryContainer,
                onTertiaryFixedVariant = darkColors.tertiaryContainer,
                primaryFixedDim = darkColors.primary,
                secondaryFixedDim = darkColors.secondary,
                tertiaryFixedDim = darkColors.tertiary,
            )
        }

        @Stable
        fun fromColorScheme(scheme: ColorScheme): FixedColorRoles {
            // Single-scheme fallback: use scheme containers for both light/dark slots.
            return fromColorSchemes(lightColors = scheme, darkColors = scheme)
        }

        val Default: FixedColorRoles =
            fromColorSchemes(
                lightColors = lightColorScheme(),
                darkColors = darkColorScheme(),
            )
    }
}

val LocalFixedColorRoles = staticCompositionLocalOf { FixedColorRoles.Default }
