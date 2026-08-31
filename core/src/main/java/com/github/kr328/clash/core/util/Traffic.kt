package com.github.kr328.clash.core.util

import com.github.kr328.clash.core.model.Traffic

fun Traffic.trafficUpload(): String {
    return trafficString(trafficUploadBytes())
}

fun Traffic.trafficDownload(): String {
    return trafficString(trafficDownloadBytes())
}

/** Decode the packed upload half into raw bytes per second / total bytes. */
fun Traffic.trafficUploadBytes(): Long {
    return decodeTrafficBytes(this ushr 32)
}

/** Decode the packed download half into raw bytes per second / total bytes. */
fun Traffic.trafficDownloadBytes(): Long {
    return decodeTrafficBytes(this and 0xFFFFFFFFL)
}

fun Traffic.trafficTotal(): String {
    // Each half carries its own unit bits, so the two can only be summed as raw bytes.
    return trafficString(trafficUploadBytes() + trafficDownloadBytes())
}

/**
 * Format raw bytes. Every caller decodes through [decodeTrafficBytes] first: the packed unit bits
 * make the same integer mean bytes or hundredths of a byte depending on the type, and formatting
 * before normalizing is what made single-value readouts wrong by 100× in both directions.
 */
private fun trafficString(bytes: Long): String {
    return when {
        bytes >= GIB -> String.format("%.2f GiB", bytes.toDouble() / GIB)
        bytes >= MIB -> String.format("%.2f MiB", bytes.toDouble() / MIB)
        bytes >= KIB -> String.format("%.2f KiB", bytes.toDouble() / KIB)
        else -> "$bytes Bytes"
    }
}

private const val KIB = 1024L
private const val MIB = KIB * 1024L
private const val GIB = MIB * 1024L

/** Decode one packed 32-bit traffic value into raw bytes. */
fun decodeTrafficBytes(value: Long): Long {
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
