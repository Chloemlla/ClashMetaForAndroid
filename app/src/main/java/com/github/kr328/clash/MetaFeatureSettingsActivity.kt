package com.github.kr328.clash

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.MetaFeatureSettingsDesign
import com.github.kr328.clash.util.clashDir
import com.github.kr328.clash.util.queryFileName
import com.github.kr328.clash.util.withClash
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.github.kr328.clash.design.R


class MetaFeatureSettingsActivity : BaseActivity<MetaFeatureSettingsDesign>() {
    override suspend fun main() {
        // A-35: a config-editing page must not show editable defaults on failure — saving them
        // would overwrite the real override with blanks. Instead surface the error and close.
        val configuration = try {
            withClash { queryOverride(Clash.OverrideSlot.Persist) }
        } catch (e: Exception) {
            runCatching {
                Toast.makeText(this, R.string.failed_to_load_settings, Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }

        defer {
            withClash {
                patchOverride(Clash.OverrideSlot.Persist, configuration)
            }
        }

        val design = MetaFeatureSettingsDesign(
            this,
            configuration
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        MetaFeatureSettingsDesign.Request.ResetOverride -> {
                            if (design.requestResetConfirm()) {
                                defer {
                                    withClash {
                                        clearOverride(Clash.OverrideSlot.Persist)
                                    }
                                }
                                finish()
                            }
                        }
                        MetaFeatureSettingsDesign.Request.ImportGeoIp -> {
                            val uri = startActivityForResult(
                                ActivityResultContracts.GetContent(),
                                "*/*")
                            importGeoFile(uri, MetaFeatureSettingsDesign.Request.ImportGeoIp)
                        }
                        MetaFeatureSettingsDesign.Request.ImportGeoSite -> {
                            val uri = startActivityForResult(
                                ActivityResultContracts.GetContent(),
                                "*/*")
                            importGeoFile(uri, MetaFeatureSettingsDesign.Request.ImportGeoSite)
                        }
                        MetaFeatureSettingsDesign.Request.ImportCountry -> {
                            val uri = startActivityForResult(
                                ActivityResultContracts.GetContent(),
                                "*/*")
                            importGeoFile(uri, MetaFeatureSettingsDesign.Request.ImportCountry)
                        }
                        MetaFeatureSettingsDesign.Request.ImportASN -> {
                            val uri = startActivityForResult(
                                ActivityResultContracts.GetContent(),
                                "*/*")
                            importGeoFile(uri, MetaFeatureSettingsDesign.Request.ImportASN)
                        }
                    }
                }
            }
        }
    }

    private val validDatabaseExtensions = listOf(
        ".metadb", ".db", ".dat", ".mmdb"
    )

    private suspend fun importGeoFile(uri: Uri?, importType: MetaFeatureSettingsDesign.Request) {
        // Dismissing the picker is a cancellation, not a failed import.
        if (uri == null) return

        val displayName = withContext(Dispatchers.IO) { uri.queryFileName(contentResolver) }

        if (displayName == null) {
            Toast.makeText(this, R.string.geofile_import_failed, Toast.LENGTH_LONG).show()
            return
        }

        val ext = "." + displayName.substringAfterLast(".")

        if (!validDatabaseExtensions.contains(ext)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.geofile_unknown_db_format)
                .setMessage(
                    getString(
                        R.string.geofile_unknown_db_format_message,
                        validDatabaseExtensions.joinToString("/")
                    )
                )
                .setPositiveButton("OK") { _, _ -> }
                .show()
            return
        }

        // B-76: write to the fixed filename the core scans (constant/path.go: MMDB()/GeoIP()/
        // GeoSite()/ASN()), regardless of the source extension. The old code preserved the source
        // extension (geoip.dat → "geoip.dat"), which the core's MMDB() loader never looks at, so
        // the import appeared to succeed but the file was never read.
        val outputFileName = when (importType) {
            MetaFeatureSettingsDesign.Request.ImportGeoIp -> "geoip.metadb"
            MetaFeatureSettingsDesign.Request.ImportGeoSite -> "geosite.dat"
            MetaFeatureSettingsDesign.Request.ImportCountry -> "Country.mmdb"
            MetaFeatureSettingsDesign.Request.ImportASN -> "ASN.mmdb"
            else -> return
        }

        // B-76: bound the import. Some providers expose a SIZE column; enforce it when present and
        // also count bytes while copying so a stream with no known size cannot grow without limit.
        val declaredSize = withContext(Dispatchers.IO) { querySizeBytes(uri) }
        if (declaredSize != null && declaredSize > MAX_GEO_IMPORT_BYTES) {
            Toast.makeText(this, R.string.geofile_import_failed, Toast.LENGTH_LONG).show()
            return
        }

        val succeeded = withContext(Dispatchers.IO) {
            val outputFile = File(clashDir, outputFileName)

            // Write to a temp file and rename into place so an interrupted copy (process death,
            // storage full) never leaves a truncated database that the kernel will silently load
            // or that extractAsset's exists() guard will treat as valid forever.
            val temp = File(clashDir, "$outputFileName.tmp")
            try {
                val input = contentResolver.openInputStream(uri) ?: return@withContext false
                input.use { ins ->
                    FileOutputStream(temp).use { outs ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val count = ins.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > MAX_GEO_IMPORT_BYTES) {
                                throw IllegalStateException("Geo database is too large")
                            }
                            outs.write(buffer, 0, count)
                        }
                    }
                }
                if (!temp.renameTo(outputFile)) {
                    temp.copyTo(outputFile, overwrite = true)
                }
                true
            } catch (e: Exception) {
                com.github.kr328.clash.common.log.Log.w("Import geo file $displayName: $e", e)
                false
            } finally {
                temp.delete()
            }
        }

        if (succeeded) {
            // B-77: mark the user-imported artifact so a later app update does not treat it as a
            // stale bundled asset and silently overwrite it with the built-in copy.
            withContext(Dispatchers.IO) {
                runCatching {
                    File(clashDir, "$outputFileName.user").writeText("user-imported")
                }
            }

            Toast.makeText(
                this,
                getString(R.string.geofile_imported, displayName),
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, R.string.geofile_import_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun querySizeBytes(uri: Uri): Long? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }.getOrNull()

    private companion object {
        const val MAX_GEO_IMPORT_BYTES = 1L * 1024L * 1024L * 1024L
    }
}