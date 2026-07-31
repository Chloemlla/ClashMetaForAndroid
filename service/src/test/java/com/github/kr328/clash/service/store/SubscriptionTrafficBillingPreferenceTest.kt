package com.github.kr328.clash.service.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SubscriptionTrafficBillingPreferenceTest {
    private val values = mutableMapOf<String, Any>()
    private val preference = SubscriptionTrafficBillingPreference(
        contains = values::containsKey,
        readBoolean = { key, _ -> values[key] as Boolean },
        writeBoolean = { key, value -> values[key] = value },
        remove = { key -> values.remove(key) },
    )

    @Test
    fun legacyDefault_isMigratedOncePerProfile() {
        val profile = UUID.fromString("00000000-0000-0000-0000-000000000001")

        assertFalse(preference.get(profile, legacyDefault = false))
        assertFalse(preference.get(profile, legacyDefault = true))
    }

    @Test
    fun missingScopedValue_canBeReadWithoutMigration() {
        val profile = UUID.fromString("00000000-0000-0000-0000-000000000001")

        assertNull(preference.getIfPresent(profile, legacyDefault = true))
        assertTrue(values.isEmpty())
    }

    @Test
    fun profileValues_areIndependent() {
        val localProfile = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val upstreamProfile = UUID.fromString("00000000-0000-0000-0000-000000000002")

        preference.set(localProfile, true)
        preference.set(upstreamProfile, false)

        assertTrue(preference.get(localProfile, legacyDefault = false))
        assertFalse(preference.get(upstreamProfile, legacyDefault = true))
        assertEquals(2, values.size)
    }

    @Test
    fun clear_restoresLegacyFallbackForThatProfileOnly() {
        val profile = UUID.fromString("00000000-0000-0000-0000-000000000001")

        preference.set(profile, false)
        preference.clear(profile)

        assertTrue(preference.get(profile, legacyDefault = true))
    }

    @Test
    fun invalidScopedValue_isRepairedFromLegacyDefault() {
        val profile = UUID.fromString("00000000-0000-0000-0000-000000000001")
        preference.set(profile, false)
        val key = values.keys.single()
        values[key] = "invalid"

        assertTrue(preference.get(profile, legacyDefault = true))
        assertEquals(true, values[key])
    }
}
