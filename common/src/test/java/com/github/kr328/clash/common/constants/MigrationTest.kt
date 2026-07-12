package com.github.kr328.clash.common.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationTest {
    @Test
    fun isMetaPackage_detectsMetaSuffix() {
        assertTrue(Migration.isMetaPackage("com.github.metacubex.clash.meta"))
        assertFalse(Migration.isMetaPackage("com.github.metacubex.clash.alpha"))
        assertFalse(Migration.isMetaPackage("com.github.metacubex.clash"))
    }

    @Test
    fun alphaPackageCandidates_mapsMetaToAlpha() {
        val candidates = Migration.alphaPackageCandidates("com.github.metacubex.clash.meta")
        assertTrue(candidates.contains("com.github.metacubex.clash.alpha"))
        assertFalse(candidates.contains("com.github.metacubex.clash.meta"))
    }

    @Test
    fun authorityAndBundleUri_usePackageName() {
        assertEquals(
            "com.github.metacubex.clash.alpha.migration",
            Migration.authorityFor("com.github.metacubex.clash.alpha"),
        )
        assertEquals(
            "content://com.github.metacubex.clash.alpha.migration/bundle",
            Migration.bundleUri("com.github.metacubex.clash.alpha"),
        )
    }
}
