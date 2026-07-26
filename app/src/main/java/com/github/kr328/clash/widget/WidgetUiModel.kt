package com.github.kr328.clash.widget

/**
 * Render-ready snapshot for home-screen widgets.
 * [sameAs] ignores pure time ticks so redraw can be skipped.
 */
data class WidgetUiModel(
    val running: Boolean,
    val profileName: String,
    val selectedNode: String,
    val mode: String,
    val ratesText: String,
    val hasRates: Boolean,
) {
    fun sameAs(other: WidgetUiModel?): Boolean {
        if (other == null) return false
        return running == other.running &&
            profileName == other.profileName &&
            selectedNode == other.selectedNode &&
            mode == other.mode &&
            ratesText == other.ratesText &&
            hasRates == other.hasRates
    }
}
