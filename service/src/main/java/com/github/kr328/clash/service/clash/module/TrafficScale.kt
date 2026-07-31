package com.github.kr328.clash.service.clash.module

import com.github.kr328.clash.core.util.decodeTrafficBytes

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
    return decodeTrafficBytes(value)
}

/**
 * Split a Clash packed upload/download [Long] into raw byte counts.
 *
 * Layout matches core traffic samples: high 32 bits = upload, low 32 bits = download.
 * Uses a Long mask so bare Int `0xFFFFFFFF` sign-extension cannot keep all bits.
 *
 * @return Pair(uploadBytes, downloadBytes)
 */
internal fun splitTrafficBytes(packed: Long): Pair<Long, Long> {
    val upload = scaleTrafficBytes(packed ushr 32)
    val download = scaleTrafficBytes(packed and 0xFFFFFFFFL)
    return upload to download
}
