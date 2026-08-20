package com.github.kr328.clash.common.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerAppsTest {
    @Test
    fun isPartnerPackage_hardcodeOnly_matchesKnownPackagesOnly() {
        assertTrue(PartnerApps.isPartnerPackage("com.chloemlla.piliplus"))
        assertTrue(PartnerApps.isPartnerPackage("com.chloemlla.nexai.debug"))
        assertTrue(PartnerApps.isPartnerPackage("com.chloemlla.projectlumen.dev"))
        assertTrue(PartnerApps.isPartnerPackage("com.chloemlla.zhplus.lite"))
        assertTrue(PartnerApps.isPartnerPackage("com.chloemlla.aura"))
        assertTrue(PartnerApps.isPartnerPackage("com.chloemlla.cdict"))
        assertTrue(PartnerApps.isPartnerPackage("com.chloemlla.cdict.debug"))
        assertFalse(PartnerApps.isPartnerPackage("com.example.unknown"))
    }

    @Test
    fun hardcodePackages_isUnionOfAllKnownPartnerFamilies() {
        val expected = PartnerApps.piliPlusPackages +
            PartnerApps.nexAiPackages +
            PartnerApps.projectLumenPackages +
            PartnerApps.zhihuPlusPackages +
            PartnerApps.auraPackages +
            PartnerApps.cdictPackages

        assertEquals(expected, PartnerApps.hardcodePackages)
        assertEquals(PartnerApps.hardcodePackages, PartnerApps.allPackages)
    }

    @Test
    fun isTruthyPartnerMetaDataValue_acceptsDocumentedTruthyForms() {
        assertTrue(PartnerApps.isTruthyPartnerMetaDataValue(true))
        assertTrue(PartnerApps.isTruthyPartnerMetaDataValue(1))
        assertTrue(PartnerApps.isTruthyPartnerMetaDataValue("1"))
        assertTrue(PartnerApps.isTruthyPartnerMetaDataValue("true"))
        assertTrue(PartnerApps.isTruthyPartnerMetaDataValue("TRUE"))
    }

    @Test
    fun isTruthyPartnerMetaDataValue_rejectsAbsentOrUnrecognizedForms() {
        assertFalse(PartnerApps.isTruthyPartnerMetaDataValue(null))
        assertFalse(PartnerApps.isTruthyPartnerMetaDataValue(false))
        assertFalse(PartnerApps.isTruthyPartnerMetaDataValue(0))
        assertFalse(PartnerApps.isTruthyPartnerMetaDataValue("0"))
        assertFalse(PartnerApps.isTruthyPartnerMetaDataValue("false"))
        assertFalse(PartnerApps.isTruthyPartnerMetaDataValue("yes"))
        assertFalse(PartnerApps.isTruthyPartnerMetaDataValue(2.0))
    }

    @Test
    fun mergePartnerPackages_keepsInstalledHardcodeUnconditionally() {
        val installedHardcode = setOf("com.chloemlla.piliplus")

        val merged = PartnerApps.mergePartnerPackages(
            installedHardcode = installedHardcode,
            candidateMetaDataPackages = emptySet(),
            trustedSigners = installedHardcode,
            signatureMatches = { _, _ -> false },
        )

        assertEquals(installedHardcode, merged)
    }

    @Test
    fun mergePartnerPackages_addsCandidateOnlyWhenSignatureMatchesATrustedSigner() {
        val installedHardcode = setOf("com.chloemlla.piliplus")
        val trustedSigners = installedHardcode + "com.github.kr328.clash"

        val merged = PartnerApps.mergePartnerPackages(
            installedHardcode = installedHardcode,
            candidateMetaDataPackages = setOf("com.example.discovered"),
            trustedSigners = trustedSigners,
            signatureMatches = { candidate, signer ->
                candidate == "com.example.discovered" && signer == "com.github.kr328.clash"
            },
        )

        assertEquals(installedHardcode + "com.example.discovered", merged)
    }

    @Test
    fun mergePartnerPackages_dropsCandidateWhenNoSignerMatches() {
        val installedHardcode = setOf("com.chloemlla.piliplus")
        val trustedSigners = installedHardcode + "com.github.kr328.clash"

        val merged = PartnerApps.mergePartnerPackages(
            installedHardcode = installedHardcode,
            candidateMetaDataPackages = setOf("com.example.untrusted"),
            trustedSigners = trustedSigners,
            signatureMatches = { _, _ -> false },
        )

        assertEquals(installedHardcode, merged)
    }

    @Test
    fun mergePartnerPackages_doesNotDoubleCountCandidateAlreadyInHardcode() {
        val installedHardcode = setOf("com.chloemlla.piliplus")

        val merged = PartnerApps.mergePartnerPackages(
            installedHardcode = installedHardcode,
            candidateMetaDataPackages = setOf("com.chloemlla.piliplus"),
            trustedSigners = installedHardcode,
            // A match here would still be harmless (union), but proves the "already
            // included" skip path also short-circuits before calling the matcher.
            signatureMatches = { _, _ -> throw AssertionError("should not be evaluated") },
        )

        assertEquals(installedHardcode, merged)
    }
}
