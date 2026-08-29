package com.github.kr328.clash.service.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerGrantTest {
    @Test
    fun encode_roundTripsThroughDecode() {
        val grant = PartnerGrant("com.chloemlla.cdict", "abcd", 1234L)

        assertEquals(grant, PartnerGrant.decode(grant.encode()))
    }

    @Test
    fun decode_acceptsLegacyTwoFieldForm() {
        assertEquals(
            PartnerGrant("com.chloemlla.cdict", "abcd", 0L),
            PartnerGrant.decode("com.chloemlla.cdict|abcd"),
        )
    }

    @Test
    fun decode_rejectsIncompleteEntries() {
        assertNull(PartnerGrant.decode(""))
        assertNull(PartnerGrant.decode("com.chloemlla.cdict"))
        assertNull(PartnerGrant.decode("|abcd"))
        assertNull(PartnerGrant.decode("com.chloemlla.cdict|"))
    }

    @Test
    fun rememberedGrantNeverExpires() {
        val grant = PartnerGrant("com.chloemlla.cdict", "abcd", 0L)

        assertTrue(grant.isValidAt(Long.MAX_VALUE))
    }

    @Test
    fun transientGrantExpiresAtItsDeadline() {
        val grant = PartnerGrant("com.chloemlla.cdict", "abcd", 1_000L)

        assertTrue(grant.isValidAt(999L))
        assertFalse(grant.isValidAt(1_000L))
        assertFalse(grant.isValidAt(1_001L))
    }
}
