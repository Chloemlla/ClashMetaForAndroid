package com.github.kr328.clash.common.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerAppsTest {
    @Test
    fun hardcodePackages_isUnionOfAllKnownPartnerFamilies() {
        val expected = PartnerApps.piliPlusPackages +
            PartnerApps.nexAiPackages +
            PartnerApps.projectLumenPackages +
            PartnerApps.zhihuPlusPackages +
            PartnerApps.auraPackages +
            PartnerApps.cdictPackages

        assertEquals(expected, PartnerApps.hardcodePackages)
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
    fun sha256Hex_producesLowercaseHexDigest() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            PartnerApps.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun sha1Hex_producesLowercaseHexDigest() {
        assertEquals(
            "da39a3ee5e6b4b0d3255bfef95601890afd80709",
            PartnerApps.sha1Hex(ByteArray(0)),
        )
    }

    @Test
    fun trustedSignerSha1_pinsExactlyTheSharedReleaseCertificate() {
        assertEquals(
            setOf("295443559574b12e12a0e49f6c92692ca0dc307a"),
            PartnerApps.trustedSignerSha1,
        )
        assertTrue(PartnerApps.trustedSignerSha1.all { Regex("[0-9a-f]{40}").matches(it) })
    }

    @Test
    fun matchesPinnedSignerSha1_acceptsPinnedFingerprintIgnoringCase() {
        val pinned = PartnerApps.trustedSignerSha1.first()

        assertTrue(PartnerApps.matchesPinnedSignerSha1(listOf(pinned)))
        assertTrue(PartnerApps.matchesPinnedSignerSha1(listOf(pinned.uppercase())))
        assertFalse(PartnerApps.matchesPinnedSignerSha1(emptyList()))
        assertFalse(PartnerApps.matchesPinnedSignerSha1(listOf("00" + pinned.drop(2))))
    }

    @Test
    fun matchesPinnedSignerSha1_rejectsTheSha256FormOfTheSameCertificate() {
        // Guards against pasting the wrong fingerprint format into the registry, which would
        // silently never match instead of failing loudly.
        assertFalse(
            PartnerApps.matchesPinnedSignerSha1(
                listOf("0403621f0e4b18e3b47049d3eea73f8df841b19e17acb7f83213e1a394d75d03"),
            ),
        )
    }

    @Test
    fun partnerPackagesFrom_acceptsAnyPackageSignedWithThePinnedCertificate() {
        val pinned = PartnerApps.trustedSignerSha1.first()
        val other = "00" + pinned.drop(2)

        val partners = PartnerApps.partnerPackagesFrom(
            mapOf(
                // Unknown applicationId, right key: a partner.
                "com.example.unlisted" to listOf(pinned),
                // Hardcoded applicationId, wrong key: not a partner.
                "com.chloemlla.piliplus" to listOf(other),
                // Multiple signers, one of them pinned.
                "com.chloemlla.cdict" to listOf(other, pinned.uppercase()),
                "com.example.unsigned" to emptyList(),
            ),
        )

        assertEquals(setOf("com.example.unlisted", "com.chloemlla.cdict"), partners)
    }
}
