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
    fun trustedSignerSha1_pinsCDictReleaseCertificateAsLowercaseHex() {
        assertTrue(
            "c4914a907e577301c616cfb30f222b4402a4ecc3" in PartnerApps.trustedSignerSha1,
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
    fun trustedSignerSha1_neverAcceptsASha256Digest() {
        // Guards against pasting the wrong fingerprint format into the SHA-1 registry, which
        // would silently never match instead of failing loudly.
        assertFalse(PartnerApps.trustedSignerSha256.any { it in PartnerApps.trustedSignerSha1 })
    }

    @Test
    fun trustedSignerSha256_pinsCDictReleaseCertificateAsLowercaseHex() {
        assertTrue(
            "8d9b6c640b027d7439e594f56682b9e31c38c7588a0c0cc02189da8c1fe91862"
                in PartnerApps.trustedSignerSha256,
        )
        assertTrue(PartnerApps.trustedSignerSha256.all { Regex("[0-9a-f]{64}").matches(it) })
    }

    @Test
    fun matchesPinnedSigner_acceptsPinnedDigestIgnoringCase() {
        val pinned = PartnerApps.trustedSignerSha256.first()

        assertTrue(PartnerApps.matchesPinnedSigner(listOf(pinned)))
        assertTrue(PartnerApps.matchesPinnedSigner(listOf(pinned.uppercase())))
    }

    @Test
    fun matchesPinnedSigner_rejectsUnknownOrMissingDigests() {
        val pinned = PartnerApps.trustedSignerSha256.first()

        assertFalse(PartnerApps.matchesPinnedSigner(emptyList()))
        assertFalse(PartnerApps.matchesPinnedSigner(listOf("00" + pinned.drop(2))))
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
