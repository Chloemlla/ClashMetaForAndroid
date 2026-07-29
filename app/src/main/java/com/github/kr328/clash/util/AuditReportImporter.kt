package com.github.kr328.clash.util

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
import java.security.MessageDigest

data class AuditReportSummary(
    val sessionId: String,
    val packageName: String,
    val limitations: List<String>,
    val evidenceFiles: List<String>,
)

object AuditReportImporter {
    private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 16L * 1024L * 1024L

    fun import(context: Context, input: InputStream): AuditReportSummary {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        buffered.mark(4)
        val header = ByteArray(4)
        val count = buffered.read(header)
        buffered.reset()
        if (count < 2 || header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte()) {
            return importJsonl(context, buffered)
        }
        val target = File(context.filesDir, "audit-reports/${System.currentTimeMillis()}").apply { mkdirs() }
        var total = 0L
        var manifest: JSONObject? = null
        val files = mutableListOf<String>()
        try {
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(!entry.isDirectory && name.isNotBlank() && !name.startsWith("/") && !name.contains("..")) { "Unsafe audit archive path" }
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
                            require(entryBytes <= MAX_ENTRY_BYTES && total <= MAX_ARCHIVE_BYTES) { "Audit archive is too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                    files += name
                    if (name == "manifest.json") manifest = JSONObject(destination.readText())
                    zip.closeEntry()
                }
            }
            val root = requireNotNull(manifest) { "Audit manifest is missing" }
            require(root.optString("protocol") == "cmfa-adb-audit") { "Unsupported audit protocol" }
            require(root.optInt("version") == 1) { "Unsupported audit manifest version" }
            val packageName = root.optString("packageName")
            require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "Invalid package name" }
            val limitations = buildList {
                val values = root.optJSONArray("limitations") ?: return@buildList
                for (i in 0 until values.length()) add(values.optString(i))
            }
            val hashes = root.optJSONObject("artifactHashes")
            if (hashes != null) {
                for (i in 0 until hashes.length()) {
                    val name = hashes.names()?.optString(i) ?: continue
                    val file = File(target, "artifacts/$name")
                    require(file.isFile && sha256(file) == hashes.optString(name).lowercase()) { "Audit artifact hash mismatch" }
                }
            }
            return AuditReportSummary(root.optString("sessionId", "unknown"), packageName, limitations, files.sorted())
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
        val target = File(context.filesDir, "audit-reports/${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val manifest = reader.readLine()?.let(::JSONObject) ?: error("Audit JSONL is empty")
            require(manifest.optString("protocol") == "cmfa-adb-audit") { "Unsupported audit protocol" }
            require(manifest.optInt("version") == 1) { "Unsupported audit manifest version" }
            val packageName = manifest.optString("packageName")
            require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "Invalid package name" }
            val records = File(target, "records.jsonl")
            records.outputStream().bufferedWriter().use { output ->
                output.appendLine(manifest.toString())
                reader.forEachLine { line -> if (line.length <= MAX_ENTRY_BYTES) output.appendLine(line) }
            }
            val limitations = buildList {
                val values = manifest.optJSONArray("limitations") ?: return@buildList
                for (i in 0 until values.length()) add(values.optString(i))
            }
            return AuditReportSummary(manifest.optString("sessionId", "unknown"), packageName, limitations, listOf("records.jsonl"))
        } catch (error: Exception) {
            target.deleteRecursively()
            throw error
        }
    }
}

