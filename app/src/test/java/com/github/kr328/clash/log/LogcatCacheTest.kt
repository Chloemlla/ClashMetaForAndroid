package com.github.kr328.clash.log

import com.github.kr328.clash.core.model.LogMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class LogcatCacheTest {
    @Test
    fun snapshot_reportsExactInsertAndRemovalRangesAtCapacity() = runBlocking {
        val cache = LogcatCache()

        repeat(LogcatCache.CAPACITY) { cache.append(message(it)) }
        val initial = requireNotNull(cache.snapshot(full = true))
        assertEquals(LogcatCache.CAPACITY, initial.messages.size)
        assertEquals(0, initial.removed)
        assertEquals(LogcatCache.CAPACITY, initial.appended)

        repeat(3) { cache.append(message(LogcatCache.CAPACITY + it)) }
        val update = requireNotNull(cache.snapshot(full = false))
        assertEquals(LogcatCache.CAPACITY, update.messages.size)
        assertEquals(3, update.removed)
        assertEquals(3, update.appended)
        assertNull(cache.snapshot(full = false))
    }

    @Test
    fun firstFullSnapshot_neverReportsMoreItemsThanItContains() = runBlocking {
        val cache = LogcatCache()

        repeat(LogcatCache.CAPACITY * 2) { cache.append(message(it)) }
        val snapshot = requireNotNull(cache.snapshot(full = true))

        assertEquals(LogcatCache.CAPACITY, snapshot.messages.size)
        assertEquals(LogcatCache.CAPACITY, snapshot.appended)
    }

    private fun message(index: Int) = LogMessage(
        level = LogMessage.Level.Info,
        message = index.toString(),
        time = Date(index.toLong()),
    )
}
