package com.github.kr328.clash.service.clash.module

/**
 * Decode Clash packed traffic samples into raw byte counts.
 *
 * Core packs values with two-decimal precision via `down_scale_traffic`
 * (value * 100 / unit). Types 1–3 must divide that factor back out;
 * otherwise local-from-0 billing accumulates ~100× inflated totals.
 *
 * Kept in a dedicated file so the pure decode path is easy to reason about
 * and unit-test without Android notification machinery.
 */
internal fun scaleTrafficBytes(value: Long): Long {
    val type = (value ushr 30) and 0x3
    val data = value and 0x3FFFFFFF
    return when (type) {
        0L -> data
        1L -> data * 1024L / 100L
        2L -> data * 1024L * 1024L / 100L
        3L -> data * 1024L * 1024L * 1024L / 100L
        else -> 0L
    }
}
