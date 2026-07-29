package com.github.kr328.clash.design.util

/**
 * Threshold, in dp, matching the `sw600dp` resource qualifier used by
 * `layout-sw600dp/design_proxy.xml` to select the Proxy dual-pane host.
 *
 * Kept as a single named constant so the layout-qualifier threshold and any
 * runtime width-class decision (e.g. deciding the initial pane state) stay in sync.
 */
const val DUAL_PANE_MIN_WIDTH_DP = 600

/**
 * Pure width-class decision helper: does [dpWidth] qualify for the large-screen
 * dual-pane layout? Mirrors the `sw600dp` qualifier so this is unit-testable without
 * inflating a real [android.content.res.Configuration] / [android.content.res.Resources].
 */
fun isDualPaneWidth(dpWidth: Int): Boolean = dpWidth >= DUAL_PANE_MIN_WIDTH_DP
