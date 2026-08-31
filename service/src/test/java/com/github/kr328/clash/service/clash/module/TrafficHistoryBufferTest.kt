package com.github.kr328.clash.service.clash.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficHistoryBufferTest {
    @Test
    fun append_wrapsAndStaysBounded() {
        val buffer = TrafficHistoryBuffer(capacity = 3, minIntervalMs = 0)

        assertTrue(buffer.tryAppend(sample(1, 10, 20), elapsedMs = 1))
        assertTrue(buffer.tryAppend(sample(2, 11, 21), elapsedMs = 2))
        assertTrue(buffer.tryAppend(sample(3, 12, 22), elapsedMs = 3))
        assertEquals(3, buffer.size())

        assertTrue(buffer.tryAppend(sample(4, 13, 23), elapsedMs = 4))
        assertEquals(3, buffer.size())

        val snap = buffer.snapshot()
        assertEquals(listOf(2L, 3L, 4L), snap.map { it.epochMs })
        assertEquals(13L, snap.last().upRateBytesPerSec)
        assertEquals(23L, snap.last().downRateBytesPerSec)
    }

    @Test
    fun snapshot_isChronologicalAfterPartialFill() {
        val buffer = TrafficHistoryBuffer(capacity = 5, minIntervalMs = 0)
        buffer.tryAppend(sample(100, 1, 2), elapsedMs = 100)
        buffer.tryAppend(sample(200, 3, 4), elapsedMs = 200)
        buffer.tryAppend(sample(300, 5, 6), elapsedMs = 300)

        assertEquals(listOf(100L, 200L, 300L), buffer.snapshot().map { it.epochMs })
    }

    @Test
    fun minInterval_rejectsDenseSamples() {
        val buffer = TrafficHistoryBuffer(capacity = 10, minIntervalMs = 2000)

        assertTrue(buffer.tryAppend(sample(1_000, 1, 1), elapsedMs = 1_000))
        assertFalse(buffer.shouldAccept(2_500))
        assertFalse(buffer.tryAppend(sample(2_500, 2, 2), elapsedMs = 2_500))
        assertTrue(buffer.shouldAccept(3_000))
        assertTrue(buffer.tryAppend(sample(3_000, 3, 3), elapsedMs = 3_000))

        assertEquals(2, buffer.size())
        assertEquals(listOf(1_000L, 3_000L), buffer.snapshot().map { it.epochMs })
    }

    @Test
    fun minInterval_ignoresWallClockStepBackwards() {
        val buffer = TrafficHistoryBuffer(capacity = 10, minIntervalMs = 2000)

        assertTrue(buffer.tryAppend(sample(10_000_000, 1, 1), elapsedMs = 1_000))
        // Wall clock jumps an hour back while the monotonic clock keeps advancing.
        assertTrue(buffer.tryAppend(sample(6_400_000, 2, 2), elapsedMs = 3_100))

        assertEquals(listOf(10_000_000L, 6_400_000L), buffer.snapshot().map { it.epochMs })
    }

    @Test
    fun clear_resetsCapacityAndIntervalGate() {
        val buffer = TrafficHistoryBuffer(capacity = 2, minIntervalMs = 1000)
        buffer.tryAppend(sample(5_000, 1, 1), elapsedMs = 5_000)
        buffer.clear()

        assertEquals(0, buffer.size())
        assertTrue(buffer.snapshot().isEmpty())
        assertTrue(buffer.shouldAccept(5_100))
        assertTrue(buffer.tryAppend(sample(5_100, 9, 9), elapsedMs = 5_100))
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
