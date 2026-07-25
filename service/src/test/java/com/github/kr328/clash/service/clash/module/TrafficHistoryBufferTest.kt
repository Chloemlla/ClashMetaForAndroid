package com.github.kr328.clash.service.clash.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficHistoryBufferTest {
    @Test
    fun append_wrapsAndStaysBounded() {
        val buffer = TrafficHistoryBuffer(capacity = 3, minIntervalMs = 0)

        assertTrue(buffer.tryAppend(sample(1, 10, 20)))
        assertTrue(buffer.tryAppend(sample(2, 11, 21)))
        assertTrue(buffer.tryAppend(sample(3, 12, 22)))
        assertEquals(3, buffer.size())

        assertTrue(buffer.tryAppend(sample(4, 13, 23)))
        assertEquals(3, buffer.size())

        val snap = buffer.snapshot()
        assertEquals(listOf(2L, 3L, 4L), snap.map { it.epochMs })
        assertEquals(13L, snap.last().upRateBytesPerSec)
        assertEquals(23L, snap.last().downRateBytesPerSec)
    }

    @Test
    fun snapshot_isChronologicalAfterPartialFill() {
        val buffer = TrafficHistoryBuffer(capacity = 5, minIntervalMs = 0)
        buffer.tryAppend(sample(100, 1, 2))
        buffer.tryAppend(sample(200, 3, 4))
        buffer.tryAppend(sample(300, 5, 6))

        assertEquals(listOf(100L, 200L, 300L), buffer.snapshot().map { it.epochMs })
    }

    @Test
    fun minInterval_rejectsDenseSamples() {
        val buffer = TrafficHistoryBuffer(capacity = 10, minIntervalMs = 2000)

        assertTrue(buffer.tryAppend(sample(1_000, 1, 1)))
        assertFalse(buffer.shouldAccept(2_500))
        assertFalse(buffer.tryAppend(sample(2_500, 2, 2)))
        assertTrue(buffer.shouldAccept(3_000))
        assertTrue(buffer.tryAppend(sample(3_000, 3, 3)))

        assertEquals(2, buffer.size())
        assertEquals(listOf(1_000L, 3_000L), buffer.snapshot().map { it.epochMs })
    }

    @Test
    fun clear_resetsCapacityAndIntervalGate() {
        val buffer = TrafficHistoryBuffer(capacity = 2, minIntervalMs = 1000)
        buffer.tryAppend(sample(5_000, 1, 1))
        buffer.clear()

        assertEquals(0, buffer.size())
        assertTrue(buffer.snapshot().isEmpty())
        assertTrue(buffer.shouldAccept(5_100))
        assertTrue(buffer.tryAppend(sample(5_100, 9, 9)))
    }

    @Test
    fun splitTrafficBytes_matchesLocalAccountingSemantics() {
        // High 32: type0 raw 42 upload; low 32: type0 raw 7 download.
        val packed = (42L shl 32) or 7L
        assertEquals(42L to 7L, splitTrafficBytes(packed))

        // 1.00 KiB up (type=1,data=100) and 2.00 MiB down (type=2,data=200).
        val upPacked = (1L shl 30) or 100L
        val downPacked = (2L shl 30) or 200L
        val mixed = (upPacked shl 32) or downPacked
        assertEquals(
            1024L to (200L * 1024L * 1024L / 100L),
            splitTrafficBytes(mixed),
        )
    }

    private fun sample(epochMs: Long, up: Long, down: Long): TrafficHistorySample {
        return TrafficHistorySample(
            epochMs = epochMs,
            upRateBytesPerSec = up,
            downRateBytesPerSec = down,
            upTotalBytes = up * 10,
            downTotalBytes = down * 10,
        )
    }
}
