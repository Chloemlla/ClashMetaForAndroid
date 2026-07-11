package com.github.kr328.clash.design.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DiffTest {
    @Test
    fun preserveOrderFrom_keepsExistingItemsStableAndAppendsNewItems() {
        val previous = listOf("b", "a", "c")
        val incoming = listOf("a", "d", "b")

        assertEquals(
            listOf("b", "a", "d"),
            incoming.preserveOrderFrom(previous) { it },
        )
    }

    @Test
    fun preserveOrderFrom_returnsIncomingOrderWithoutPreviousItems() {
        val incoming = listOf("c", "a", "b")

        assertEquals(incoming, incoming.preserveOrderFrom(emptyList()) { it })
    }
}
