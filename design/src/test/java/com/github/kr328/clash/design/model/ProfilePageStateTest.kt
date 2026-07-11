package com.github.kr328.clash.design.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePageStateTest {
    @Test
    fun updateAll_isSingleFlightAndCanRecover() {
        val state = ProfilePageState()

        assertTrue(state.beginUpdateAll())
        assertFalse(state.beginUpdateAll())

        state.finishUpdateAll()

        assertTrue(state.beginUpdateAll())
    }
}
