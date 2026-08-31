package com.github.kr328.clash.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
import java.security.MessageDigest
import java.util.UUID

data class AuditReportSummary(
    val sessionId: String,
    val packageName: String,
    val limitations: List<String>,
    val evidenceFiles: List<String>,
    val redactionApplied: Boolean,
    val authorizationReference: String?,
    val deviceLabel: String?,
)

object AuditReportImporter {
    fun import(context: Context, input: InputStream): AuditReportSummary {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        buffered.mark(4)
        val header = ByteArray(4)
        val count = buffered.read(header)
        buffered.reset()
        if (count < 2 || header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte()) {
            return importJsonl(context, buffered)
        }
        val target = createTarget(context)
        var total = 0L
        var entryCount = 0
        var manifest: JSONObject? = null
        val files = mutableListOf<String>()
        val seenEntries = mutableSetOf<String>()
        try {
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    require(entryCount <= AuditReportPolicy.MAX_ENTRY_COUNT) { "Audit archive has too many entries" }
                    val name = entry.name.replace('\\', '/')
                    val logicalName = name.removeSuffix("/")
                    require(AuditReportPolicy.isSafeArchiveEntry(logicalName)) { "Unsafe audit archive path" }
                    require(seenEntries.add(name)) { "Duplicate audit archive entry" }
                    if (entry.isDirectory) {
                        require(AuditReportPolicy.isAllowedArchiveDirectory(logicalName)) {
                            "Unexpected audit archive directory"
                        }
                        zip.closeEntry()
                        continue
                    }
                    require(AuditReportPolicy.isAllowedArchiveFile(name)) { "Unexpected audit archive file" }
                    val destination = File(target, name)
                    require(destination.canonicalPath.startsWith(target.canonicalPath + File.separator)) { "Unsafe audit archive path" }
                    destination.parentFile?.mkdirs()
                    var entryBytes = 0L
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            total += count
                            require(
                                entryBytes <= AuditReportPolicy.maximumEntryBytes(name) &&
                                    total <= AuditReportPolicy.MAX_ARCHIVE_BYTES
                            ) { "Audit archive is too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                    files += name
                    if (name == "manifest.json") manifest = JSONObject(destination.readText().removePrefix("\uFEFF"))
                    zip.closeEntry()
                }
            }
            val root = requireNotNull(manifest) { "Audit manifest is missing" }
            require(REQUIRED_ZIP_FILES.all(files::contains)) { "Audit archive is incomplete" }
            val validated = validateManifest(root)
            val hashes = requireNotNull(root.optJSONObject("artifactHashes")) {
                "Audit artifact hashes are missing"
            }
            val artifactFiles = files
                .filter { it.startsWith("artifacts/") }
                .map { it.removePrefix("artifacts/") }
                .toSet()
            val hashedArtifacts = mutableSetOf<String>()
            for (i in 0 until hashes.length()) {
                val name = hashes.names()?.optString(i) ?: continue
                val expectedHash = hashes.optString(name)
                require(AuditReportPolicy.isSafeArtifactName(name)) { "Unsafe audit artifact name" }
                require(AuditReportPolicy.isValidSha256(expectedHash)) { "Invalid audit artifact hash" }
                val file = File(target, "artifacts/$name")
                require(file.isFile && sha256(file) == expectedHash.lowercase()) { "Audit artifact hash mismatch" }
                hashedArtifacts += name
            }
            require(artifactFiles == hashedArtifacts) { "Every audit artifact must have exactly one SHA-256 hash" }
            val recordsFile = File(target, "records.jsonl")
            require(recordsFile.isFile) { "Audit records are missing" }
            validateRecords(
                recordsFile,
                validated.sessionId,
                validated.packageName,
                validated.redactionApplied,
            )
            validateReport(
                report = File(target, "report.jsonl"),
                manifest = root,
                records = recordsFile,
            )
            return AuditReportSummary(
                sessionId = validated.sessionId,
                packageName = validated.packageName,
                limitations = validated.limitations,
                evidenceFiles = files.sorted(),
                redactionApplied = validated.redactionApplied,
                authorizationReference = validated.authorizationReference,
                deviceLabel = validated.deviceLabel,
            )
        } catch (error: Exception) {
            target.deleteRecursively()
            throw error
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun importJsonl(context: Context, input: InputStream): AuditReportSummary {
        val target = createTarget(context)
        try {
            // B-97: single-pass streaming. The old code read the whole file into a ByteArray and
            // then decoded it again (raw bytes + decoded string + output buffer \u2248 3x peak). Here
            // only one line at a time is in memory; the total-size cap is a cumulative counter.
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val manifestLine = reader.readLine()?.removePrefix("\uFEFF") ?: error("Audit JSONL is empty")
            require(manifestLine.toByteArray(Charsets.UTF_8).size <= AuditReportPolicy.MAX_MANIFEST_BYTES) {
                "Audit manifest is too large"
            }
            val manifest = JSONObject(manifestLine)
            val validated = validateManifest(manifest)
            require((manifest.optJSONObject("artifactHashes")?.length() ?: 0) == 0) {
                "Use the ZIP report when external artifacts are present"
            }
            val report = File(target, "report.jsonl")
            var recordCount = 0
            var totalBytes = manifestLine.toByteArray(Charsets.UTF_8).size.toLong()
            report.outputStream().bufferedWriter().use { output ->
                output.appendLine(manifest.toString())
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    totalBytes += line.toByteArray(Charsets.UTF_8).size + 1L
                    require(totalBytes <= AuditReportPolicy.MAX_JSONL_BYTES) { "Audit JSONL is too large" }
                    recordCount += 1
                    require(recordCount <= AuditReportPolicy.MAX_RECORD_COUNT) { "Audit JSONL has too many records" }
                    val record = JSONObject(line)
                    validateRecord(record, validated.sessionId, validated.packageName, validated.redactionApplied)
                    output.appendLine(line)
                }
            }
            require(recordCount > 0) { "Audit records are empty" }
            return AuditReportSummary(
                sessionId = validated.sessionId,
                packageName = validated.packageName,
                limitations = validated.limitations,
                evidenceFiles = listOf("report.jsonl"),
                redactionApplied = validated.redactionApplied,
                authorizationReference = validated.authorizationReference,
                deviceLabel = validated.deviceLabel,
            )
        } catch (error: Exception) {
            target.deleteRecursively()
            throw error
        }
    }

    private fun createTarget(context: Context): File {
        val root = File(context.filesDir, "audit-reports")

        removeStaleReports(root)

        return File(root, UUID.randomUUID().toString()).apply {
            check(mkdirs()) { "Unable to create audit report directory" }
        }
    }

    /**
     * Extracted reports are never reopened — [import] returns everything the UI shows — so an
     * un-pruned root just accumulates evidence text in the app's private directory.
     * Mirrors `ProfileFileExport.removeStaleExports`.
     */
    private fun removeStaleReports(root: File) {
        val cutoff = System.currentTimeMillis() - STALE_REPORT_AGE_MILLIS

        root.listFiles()
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.deleteRecursively() }
    }

    private fun validateRecords(
        file: File,
        sessionId: String,
        packageName: String,
        redactionApplied: Boolean,
    ) {
        var recordCount = 0
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { sourceLine ->
                val line = sourceLine.removePrefix("\uFEFF")
                if (line.isBlank()) return@forEach
                recordCount += 1
                require(recordCount <= AuditReportPolicy.MAX_RECORD_COUNT) { "Audit JSONL has too many records" }
                validateRecord(JSONObject(line), sessionId, packageName, redactionApplied)
            }
        }
        require(recordCount > 0) { "Audit records are empty" }
    }

    private fun validateReport(report: File, manifest: JSONObject, records: File) {
        require(report.isFile) { "Standalone audit report is missing" }
        report.bufferedReader(Charsets.UTF_8).use { reportReader ->
            records.bufferedReader(Charsets.UTF_8).use { recordsReader ->
                val reportManifestLine = nextNonBlankLine(reportReader)
                    ?: error("Standalone audit report is empty")
                val reportManifest = JSONObject(reportManifestLine.removePrefix("\uFEFF"))
                validateManifest(reportManifest)
                require(sameJson(reportManifest, manifest)) {
                    "Standalone audit manifest does not match the ZIP manifest"
                }

                while (true) {
                    val expected = nextNonBlankLine(recordsReader)?.removePrefix("\uFEFF")
                    val actual = nextNonBlankLine(reportReader)?.removePrefix("\uFEFF")
                    require(expected == actual) { "Standalone audit records do not match records.jsonl" }
                    if (expected == null) break
                }
            }
        }
    }

    /**
     * Structural JSON equality. `JSONObject.toString()` preserves insertion order, so comparing
     * serialized forms would reject two manifests that differ only in key order.
     */
    private fun sameJson(left: Any?, right: Any?): Boolean = when {
        left is JSONObject && right is JSONObject ->
            left.length() == right.length() &&
                left.keys().asSequence().all { right.has(it) && sameJson(left.get(it), right.get(it)) }
        left is JSONArray && right is JSONArray ->
            left.length() == right.length() &&
                (0 until left.length()).all { sameJson(left.get(it), right.get(it)) }
        else -> left == right
    }

    private fun nextNonBlankLine(reader: BufferedReader): String? {
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isNotBlank()) return line
        }
    }

    internal fun validateRecord(
        record: JSONObject,
        sessionId: String,
        packageName: String,
        redactionApplied: Boolean,
    ) {
        require(requiredString(record, "sessionId") == sessionId) { "Audit record session mismatch" }
        require(requiredString(record, "packageName") == packageName) { "Audit record package mismatch" }
        require(AuditReportPolicy.isValidUtcTimestamp(requiredString(record, "timestamp"))) {
            "Audit record timestamp is invalid"
        }
        require(AuditReportPolicy.isSafeMetadataText(requiredString(record, "source"))) {
            "Audit record source is invalid"
        }
        require(AuditReportPolicy.isSafeMetadataText(requiredString(record, "kind"))) {
            "Audit record kind is invalid"
        }
        val recordRedacted = record.opt("redacted") as? Boolean
        require(recordRedacted != null) { "Audit record redaction status is missing" }
        require(recordRedacted == redactionApplied) {
            "Audit record contradicts manifest redaction status"
        }
        require(record.opt("data") is String) { "Audit record data is missing" }
    }

    private fun validateManifest(root: JSONObject): ValidatedManifest {
        val authorization = requireNotNull(root.optJSONObject("authorization")) {
            "Audit authorization metadata is missing"
        }
        val redaction = requireNotNull(root.optJSONObject("redaction")) {
            "Audit redaction metadata is missing"
        }
        val capabilities = requireNotNull(root.optJSONObject("capabilities")) {
            "Audit capabilities are missing"
        }
        val limitationsArray = requireNotNull(root.optJSONArray("limitations")) {
            "Audit limitations are missing"
        }
        val deviceInfo = requireNotNull(root.optJSONObject("deviceInfo")) {
            "Audit device metadata is missing"
        }
        val metadata = AuditReportPolicy.ManifestMetadata(
            protocol = requiredString(root, "protocol"),
            version = (root.opt("version") as? Number)?.toInt() ?: -1,
            sessionId = requiredString(root, "sessionId"),
            packageName = requiredString(root, "packageName"),
            startedAt = requiredString(root, "startedAt"),
            finishedAt = requiredString(root, "finishedAt"),
            authorizationConfirmed = authorization.opt("confirmed") as? Boolean,
            authorizationConfirmedAt = requiredString(authorization, "confirmedAt"),
            authorizationScope = requiredString(authorization, "scope"),
            redactionApplied = redaction.opt("applied") as? Boolean,
            redactionMode = requiredString(redaction, "mode"),
            redactionScope = requiredString(redaction, "scope"),
            externalArtifactsRedacted = redaction.opt("externalArtifactsRedacted") as? Boolean,
            hasCapabilities = true,
            hasLimitations = true,
            hasArtifactHashes = root.optJSONObject("artifactHashes") != null,
        )
        AuditReportPolicy.validateManifest(metadata)

        val capabilityValues = mutableMapOf<String, Boolean?>()
        val capabilityNames = capabilities.keys()
        while (capabilityNames.hasNext()) {
            val name = capabilityNames.next()
            capabilityValues[name] = capabilities.opt(name) as? Boolean
        }
        AuditReportPolicy.validateCapabilities(capabilityValues)

        val limitations = buildList {
            for (index in 0 until limitationsArray.length()) {
                add(limitationsArray.opt(index) as? String ?: error("Invalid audit capability gap"))
            }
        }
        AuditReportPolicy.validateLimitations(limitations)

        val model = requiredString(deviceInfo, "model")
        val androidVersion = requiredString(deviceInfo, "androidVersion")
        val sdk = requiredString(deviceInfo, "sdk")
        require(listOf(model, androidVersion, sdk).all {
            AuditReportPolicy.isSafeMetadataText(it)
        }) {
            "Invalid audit device metadata"
        }
        val authorizationReference = optionalString(authorization, "reference")
            ?.takeIf { it.isNotBlank() }
        authorizationReference?.let {
            require(AuditReportPolicy.isSafeMetadataText(it)) { "Invalid audit authorization reference" }
        }

        return ValidatedManifest(
            sessionId = metadata.sessionId,
            packageName = metadata.packageName,
            limitations = limitations,
            redactionApplied = metadata.redactionApplied == true,
            authorizationReference = authorizationReference,
            deviceLabel = "$model · Android $androidVersion · SDK $sdk",
        )
    }

    private fun requiredString(value: JSONObject, name: String): String =
        (value.opt(name) as? String)?.takeIf { it.isNotBlank() }
            ?: error("Audit $name is missing")

    private fun optionalString(value: JSONObject, name: String): String? =
        value.opt(name)?.takeUnless { it == JSONObject.NULL } as? String

    private data class ValidatedManifest(
        val sessionId: String,
        val packageName: String,
        val limitations: List<String>,
        val redactionApplied: Boolean,
        val authorizationReference: String?,
        val deviceLabel: String,
    )

    private val REQUIRED_ZIP_FILES = setOf("manifest.json", "records.jsonl", "report.jsonl")

    private const val STALE_REPORT_AGE_MILLIS = 24L * 60L * 60L * 1000L
}

