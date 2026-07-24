package com.github.kr328.clash.service.clash.module

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficScaleTest {
    @Test
    fun rawBytes_type0_passThrough() {
        assertEquals(512L, scaleTrafficBytes(512L))
        assertEquals(0L, scaleTrafficBytes(0L))
    }

    @Test
    fun kibPacked_dividesDisplayScale() {
        // 1.00 KiB is packed as type=1, data=100 → 1024 bytes after /100.
        val packed = (1L shl 30) or 100L
        assertEquals(1024L, scaleTrafficBytes(packed))
    }

    @Test
    fun mibPacked_dividesDisplayScale() {
        // 1.00 MiB is packed as type=2, data=100 → 1024*1024 bytes after /100.
        val packed = (2L shl 30) or 100L
        assertEquals(1024L * 1024L, scaleTrafficBytes(packed))
    }

    @Test
    fun gibPacked_dividesDisplayScale() {
        // 2.50 GiB is packed as type=3, data=250.
        val packed = (3L shl 30) or 250L
        assertEquals(250L * 1024L * 1024L * 1024L / 100L, scaleTrafficBytes(packed))
    }

    @Test
    fun fractionalMib_roundsDownLikeCore() {
        // 12.34 MiB packed as type=2, data=1234 → floor(1234 * 1MiB / 100).
        val packed = (2L shl 30) or 1234L
        assertEquals(1234L * 1024L * 1024L / 100L, scaleTrafficBytes(packed))
    }
}
