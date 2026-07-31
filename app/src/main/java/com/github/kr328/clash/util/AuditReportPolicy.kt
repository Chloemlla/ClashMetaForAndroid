package com.github.kr328.clash.util

import java.time.Instant
import java.util.UUID

internal object AuditReportPolicy {
    const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
    const val MAX_ENTRY_BYTES = 48L * 1024L * 1024L
    const val MAX_MANIFEST_BYTES = 1024L * 1024L
    const val MAX_JSONL_BYTES = 16L * 1024L * 1024L
    const val MAX_ENTRY_COUNT = 512
    const val MAX_RECORD_COUNT = 10_000
    const val MAX_ENTRY_NAME_LENGTH = 512
    const val MAX_LIMITATION_COUNT = 128
    const val MAX_METADATA_TEXT_LENGTH = 4096

    val requiredCapabilities = setOf(
        "adb",
        "root",
        "tcpdump",
        "pcapdroid",
        "dns",
        "mitmproxy",
        "httpsParameters",
        "frida",
        "runtimeHooks",
    )

    private val packageNamePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")
    private val windowsDrivePattern = Regex("^[A-Za-z]:/")
    private val bidiControlPattern = Regex("[\\u202A-\\u202E\\u2066-\\u2069]")

    data class ManifestMetadata(
        val protocol: String,
        val version: Int,
        val sessionId: String,
        val packageName: String,
        val startedAt: String,
        val finishedAt: String,
        val authorizationConfirmed: Boolean?,
        val authorizationConfirmedAt: String,
        val authorizationScope: String,
        val redactionApplied: Boolean?,
        val redactionMode: String,
        val redactionScope: String,
        val externalArtifactsRedacted: Boolean?,
        val hasCapabilities: Boolean,
        val hasLimitations: Boolean,
        val hasArtifactHashes: Boolean,
    )

    fun isValidPackageName(value: String): Boolean = packageNamePattern.matches(value)

    fun isValidSessionId(value: String): Boolean = runCatching {
        UUID.fromString(value)
    }.isSuccess

    fun isSafeArchiveEntry(value: String): Boolean {
        if (value.isBlank() || value.length > MAX_ENTRY_NAME_LENGTH) return false
        if (value.any { it.code < 0x20 || it.code == 0x7f } || bidiControlPattern.containsMatchIn(value)) {
            return false
        }
        val normalized = value.replace('\\', '/')
        if (normalized.startsWith('/') || windowsDrivePattern.containsMatchIn(normalized)) return false
        val segments = normalized.split('/')
        return segments.none { it.isEmpty() || it == "." || it == ".." }
    }

    fun isSafeArtifactName(value: String): Boolean =
        isSafeArchiveEntry(value) && '/' !in value && '\\' !in value

    fun isValidSha256(value: String): Boolean = sha256Pattern.matches(value)

    fun isAllowedArchiveDirectory(value: String): Boolean = value == "artifacts"

    fun isAllowedArchiveFile(value: String): Boolean = when (value) {
        "manifest.json", "records.jsonl", "report.jsonl" -> true
        else -> value.startsWith("artifacts/") &&
            isSafeArtifactName(value.removePrefix("artifacts/"))
    }

    fun maximumEntryBytes(value: String): Long = when (value) {
        "manifest.json" -> MAX_MANIFEST_BYTES
        "records.jsonl", "report.jsonl" -> MAX_JSONL_BYTES
        else -> MAX_ENTRY_BYTES
    }

    fun isValidUtcTimestamp(value: String): Boolean = runCatching {
        Instant.parse(value)
    }.isSuccess

    fun isSafeMetadataText(value: String, allowEmpty: Boolean = false): Boolean {
        if ((!allowEmpty && value.isBlank()) || value.length > MAX_METADATA_TEXT_LENGTH) return false
        return value.none { it.code < 0x20 || it.code == 0x7f } &&
            !bidiControlPattern.containsMatchIn(value)
    }

    fun validateManifest(metadata: ManifestMetadata) {
        require(metadata.protocol == "cmfa-adb-audit") { "Unsupported audit protocol" }
        require(metadata.version == 1) { "Unsupported audit manifest version" }
        require(isValidSessionId(metadata.sessionId)) { "Invalid audit session ID" }
        require(isValidPackageName(metadata.packageName)) { "Invalid package name" }
        require(isValidUtcTimestamp(metadata.startedAt) && isValidUtcTimestamp(metadata.finishedAt)) {
            "Invalid audit session timestamp"
        }
        require(!Instant.parse(metadata.finishedAt).isBefore(Instant.parse(metadata.startedAt))) {
            "Audit session finish precedes its start"
        }
        require(metadata.authorizationConfirmed == true) { "Audit authorization confirmation is missing" }
        require(isValidUtcTimestamp(metadata.authorizationConfirmedAt)) {
            "Audit authorization timestamp is missing"
        }
        require(isSafeMetadataText(metadata.authorizationScope)) { "Audit authorization scope is missing" }
        val redactionApplied = requireNotNull(metadata.redactionApplied) {
            "Audit redaction status is missing"
        }
        require(
            (redactionApplied && metadata.redactionMode == "Default") ||
                (!redactionApplied && metadata.redactionMode == "None")
        ) { "Audit redaction mode is inconsistent" }
        require(isSafeMetadataText(metadata.redactionScope)) { "Audit redaction scope is missing" }
        require(metadata.externalArtifactsRedacted != null) {
            "External artifact redaction metadata is missing"
        }
        require(metadata.hasCapabilities) { "Audit capabilities are missing" }
        require(metadata.hasLimitations) { "Audit limitations are missing" }
        require(metadata.hasArtifactHashes) { "Audit artifact hashes are missing" }
    }

    fun validateCapabilities(values: Map<String, Boolean?>) {
        require(values.keys == requiredCapabilities && values.values.none { it == null }) {
            "Audit capabilities are incomplete"
        }
    }

    fun validateLimitations(values: List<String>) {
        require(values.size <= MAX_LIMITATION_COUNT) { "Too many audit capability gaps" }
        require(values.all { isSafeMetadataText(it) }) { "Invalid audit capability gap" }
    }
}
