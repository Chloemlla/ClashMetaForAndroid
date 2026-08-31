package com.github.kr328.clash.widget

/**
 * Render-ready snapshot for home-screen widgets.
 *
 * Every field is render-visible, so the generated `equals` is exactly the redraw-needed test —
 * a hand-rolled comparison would silently stop covering fields added later.
 */
data class WidgetUiModel(
    val running: Boolean,
    val profileName: String,
    val selectedNode: String,
    val mode: String,
    val ratesText: String,
    val hasRates: Boolean,
)
