package com.github.kr328.clash.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditReportPolicyTest {
    @Test
    fun packageNameValidationRejectsCommandLikeInput() {
        assertTrue(AuditReportPolicy.isValidPackageName("com.example.target"))
        assertFalse(AuditReportPolicy.isValidPackageName("com.example;rm"))
        assertFalse(AuditReportPolicy.isValidPackageName("single"))
        assertFalse(AuditReportPolicy.isValidPackageName("com.1invalid"))
    }

    @Test
    fun archiveEntryValidationRejectsTraversalAndAbsolutePaths() {
        assertTrue(AuditReportPolicy.isSafeArchiveEntry("artifacts/capture.pcapng"))
        assertTrue(AuditReportPolicy.isSafeArchiveEntry("records..backup.jsonl"))
        assertFalse(AuditReportPolicy.isSafeArchiveEntry("../manifest.json"))
        assertFalse(AuditReportPolicy.isSafeArchiveEntry("artifacts/../manifest.json"))
        assertFalse(AuditReportPolicy.isSafeArchiveEntry("/absolute/path"))
        assertFalse(AuditReportPolicy.isSafeArchiveEntry("C:/absolute/path"))
        assertFalse(AuditReportPolicy.isSafeArchiveEntry("artifacts\\..\\manifest.json"))
        assertFalse(AuditReportPolicy.isSafeArchiveEntry("artifacts/unsafe\nname"))
        assertFalse(AuditReportPolicy.isSafeArchiveEntry("artifacts/\u202Egpj.exe"))
    }

    @Test
    fun artifactValidationRequiresFlatNameAndSha256() {
        assertTrue(AuditReportPolicy.isSafeArtifactName("pcapdroid-pcap-capture.pcapng"))
        assertFalse(AuditReportPolicy.isSafeArtifactName("nested/capture.pcapng"))
        assertTrue(AuditReportPolicy.isValidSha256("a".repeat(64)))
        assertFalse(AuditReportPolicy.isValidSha256("a".repeat(63)))
    }

    @Test
    fun manifestValidationRequiresConsentAndRedactionMetadata() {
        val sessionId = "123e4567-e89b-12d3-a456-426614174000"
        val valid = AuditReportPolicy.ManifestMetadata(
            protocol = "cmfa-adb-audit",
            version = 1,
            sessionId = sessionId,
            packageName = "com.example.target",
            startedAt = "2026-07-29T00:00:00Z",
            finishedAt = "2026-07-29T00:01:00Z",
            authorizationConfirmed = true,
            authorizationConfirmedAt = "2026-07-29T00:00:00Z",
            authorizationScope = "Authorized fixture",
            redactionApplied = true,
            redactionMode = "Default",
            redactionScope = "Generated text records",
            externalArtifactsRedacted = false,
            hasCapabilities = true,
            hasLimitations = true,
            hasArtifactHashes = true,
        )
        AuditReportPolicy.validateManifest(valid)

        assertThrows(IllegalArgumentException::class.java) {
            AuditReportPolicy.validateManifest(valid.copy(authorizationConfirmed = false))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuditReportPolicy.validateManifest(valid.copy(redactionApplied = null))
        }
    }

    @Test
    fun archiveContractAllowsOnlyExpectedFilesAndValidUtcTime() {
        assertTrue(AuditReportPolicy.isAllowedArchiveDirectory("artifacts"))
        assertTrue(AuditReportPolicy.isAllowedArchiveFile("manifest.json"))
        assertTrue(AuditReportPolicy.isAllowedArchiveFile("artifacts/capture.pcapng"))
        assertFalse(AuditReportPolicy.isAllowedArchiveFile("unexpected.bin"))
        assertTrue(AuditReportPolicy.isValidUtcTimestamp("2026-07-29T00:00:00Z"))
        assertFalse(AuditReportPolicy.isValidUtcTimestamp("not-a-time"))
    }

    @Test
    fun capabilityValidationRequiresTheExactBooleanContract() {
        val valid: Map<String, Boolean?> = AuditReportPolicy.requiredCapabilities.associateWith { true }
        AuditReportPolicy.validateCapabilities(valid)

        assertThrows(IllegalArgumentException::class.java) {
            AuditReportPolicy.validateCapabilities(valid - "tcpdump")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuditReportPolicy.validateCapabilities(valid + ("unexpected" to true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuditReportPolicy.validateCapabilities(valid + ("root" to null))
        }
    }
}
