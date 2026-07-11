package com.github.kr328.clash.design.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogUpdateTest {
    @Test
    fun capacityUpdate_removesFromStartAndInsertsAtRemainingTail() {
        assertEquals(
            LogUpdate(3, 125, 3),
            calculateLogUpdate(oldSize = 128, updatedSize = 128, removed = 3, appended = 3),
        )
    }

    @Test
    fun initialSnapshot_insertsFromZero() {
        assertEquals(
            LogUpdate(0, 0, 128),
            calculateLogUpdate(oldSize = 0, updatedSize = 128, removed = 0, appended = 128),
        )
    }

    @Test
    fun inconsistentCounts_requireFullRefresh() {
        assertNull(
            calculateLogUpdate(oldSize = 128, updatedSize = 128, removed = 0, appended = 128),
        )
    }
}
