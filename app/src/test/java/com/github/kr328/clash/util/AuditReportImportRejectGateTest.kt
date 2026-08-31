package com.github.kr328.clash.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-99: rejection gates that AuditReportImporter.import() relies on. The importer's `require`
 * checks map 1:1 onto these pure policy functions; org.json itself is an android.jar stub that
 * cannot run under the plain JVM unit-test runner, so the gates are tested here.
 */
class AuditReportImportRejectGateTest {
    @Test
    fun jsonlEntryCapsArePerFileKind() {
        assertEquals(AuditReportPolicy.MAX_MANIFEST_BYTES, AuditReportPolicy.maximumEntryBytes("manifest.json"))
        assertEquals(AuditReportPolicy.MAX_JSONL_BYTES, AuditReportPolicy.maximumEntryBytes("records.jsonl"))
        assertEquals(AuditReportPolicy.MAX_JSONL_BYTES, AuditReportPolicy.maximumEntryBytes("report.jsonl"))
        assertEquals(AuditReportPolicy.MAX_ENTRY_BYTES, AuditReportPolicy.maximumEntryBytes("artifacts/capture.pcapng"))
    }

    @Test
    fun onlyArtifactsDirectoryIsAllowed() {
        assertTrue(AuditReportPolicy.isAllowedArchiveDirectory("artifacts"))
        assertFalse(AuditReportPolicy.isAllowedArchiveDirectory("logs"))
        assertFalse(AuditReportPolicy.isAllowedArchiveDirectory("artifacts/"))
    }

    @Test
    fun metadataTextRejectsControlAndBidiCharacters() {
        assertTrue(AuditReportPolicy.isSafeMetadataText("normal text"))
        // NUL (code 0x00 < 0x20) and a right-to-left override (U+202E) must be rejected.
        assertFalse(AuditReportPolicy.isSafeMetadataText("bad\u0000text"))
        assertFalse(AuditReportPolicy.isSafeMetadataText("bad\u202Ertl"))
        assertFalse(AuditReportPolicy.isSafeMetadataText(" ".repeat(AuditReportPolicy.MAX_METADATA_TEXT_LENGTH + 1)))
    }

    @Test
    fun limitationListIsBoundedAndSafe() {
        AuditReportPolicy.validateLimitations(listOf("device was locked"))
        assertThrows(IllegalArgumentException::class.java) {
            AuditReportPolicy.validateLimitations(
                List(AuditReportPolicy.MAX_LIMITATION_COUNT + 1) { "gap" }
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuditReportPolicy.validateLimitations(listOf("unsafe\u0000gap"))
        }
    }

    @Test
    fun artifactHashesMustBeExactSha256() {
        assertTrue(AuditReportPolicy.isValidSha256("a".repeat(64)))
        assertFalse(AuditReportPolicy.isValidSha256("a".repeat(64).uppercase().substring(0, 63)))
        assertFalse(AuditReportPolicy.isValidSha256("z".repeat(64)))
    }
}
