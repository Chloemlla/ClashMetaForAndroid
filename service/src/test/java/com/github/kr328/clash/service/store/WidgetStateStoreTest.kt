package com.github.kr328.clash.service.store

import com.github.kr328.clash.service.model.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WidgetStateStoreTest {
    @Before
    fun reset() {
        WidgetStateStore.clear()
    }

    @Test
    fun sameAs_ignoresUpdatedAt() {
        val a = state(updatedAt = 1L)
        val b = state(updatedAt = 99L)
        assertTrue(a.sameAs(b))
        assertFalse(a.sameAs(state(upRate = 2L)))
        assertFalse(a.sameAs(null))
    }

    @Test
    fun update_isNoOpWhenSameContent() {
        val first = state(updatedAt = 10L)
        assertTrue(WidgetStateStore.update(first))
        assertEquals(first, WidgetStateStore.current())

        val second = state(updatedAt = 20L)
        assertFalse(WidgetStateStore.update(second))
        // Original snapshot retained (including original timestamp).
        assertEquals(first, WidgetStateStore.current())
    }

    @Test
    fun update_replacesWhenTrafficChanges() {
        assertTrue(WidgetStateStore.update(state(upRate = 1L, updatedAt = 1L)))
        val next = state(upRate = 5L, updatedAt = 2L)
        assertTrue(WidgetStateStore.update(next))
        assertEquals(next, WidgetStateStore.current())
    }

    @Test
    fun clear_emptiesStore() {
        WidgetStateStore.update(state())
        WidgetStateStore.clear()
        assertNull(WidgetStateStore.current())
    }

    private fun state(
        running: Boolean = true,
        profileName: String? = "home",
        mode: String = "Rule",
        selectedNode: String = "node-a",
        upRate: Long = 1L,
        downRate: Long = 2L,
        upTotal: Long = 3L,
        downTotal: Long = 4L,
        updatedAt: Long = 100L,
    ): WidgetState {
        return WidgetState(
            running = running,
            profileName = profileName,
            mode = mode,
            selectedNode = selectedNode,
            upRateBytesPerSec = upRate,
            downRateBytesPerSec = downRate,
            upTotalBytes = upTotal,
            downTotalBytes = downTotal,
            updatedAtEpochMs = updatedAt,
        )
    }
}
