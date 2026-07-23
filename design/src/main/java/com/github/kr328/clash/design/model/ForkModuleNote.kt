package com.github.kr328.clash.design.model

import androidx.annotation.StringRes
import com.github.kr328.clash.design.R

/**
 * Module-level fork improvements shown on the first-install open-source notice page.
 * Summaries stay short for the splash/onboarding surface; full inventory lives in README.
 */
data class ForkModuleNote(
    val module: String,
    @StringRes val summaryRes: Int,
) {
    companion object {
        fun defaults(): List<ForkModuleNote> = listOf(
            ForkModuleNote(":app", R.string.open_source_mod_app),
            ForkModuleNote(":design", R.string.open_source_mod_design),
            ForkModuleNote(":service", R.string.open_source_mod_service),
            ForkModuleNote(":core", R.string.open_source_mod_core),
            ForkModuleNote(":common", R.string.open_source_mod_common),
            ForkModuleNote(":sdk", R.string.open_source_mod_sdk),
            ForkModuleNote("CI / release", R.string.open_source_mod_ci),
        )
    }
}
