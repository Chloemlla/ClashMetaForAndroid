package com.github.kr328.clash

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import com.github.kr328.clash.common.util.grantPermissions
import com.github.kr328.clash.design.FilesDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.File
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.FilesClient
import com.github.kr328.clash.util.ConfigOutline
import com.github.kr328.clash.util.ProfileFileEditor
import com.github.kr328.clash.util.ProfileFileRoundTrip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Focused external-edit, validation, and outline actions for [FilesActivity]. */
internal class ProfileFileActions(
    private val activity: FilesActivity,
    private val uuid: UUID,
    private val root: String,
    private val configurationEditable: Boolean,
    private val client: FilesClient,
    private val design: FilesDesign,
) {
    fun isConfiguration(file: File): Boolean = file.id == "$root/config.yaml"

    fun mimeType(file: File): String {
        return if (file.isYaml()) ProfileFileEditor.MIME_YAML else ProfileFileEditor.MIME_TEXT
    }

    suspend fun open(file: File) {
        if (isConfiguration(file) && configurationEditable) {
            openConfigurationEditor(file)
            return
        }

        val uri = client.buildDocumentUri(file.id)
        val writable = !isConfiguration(file)
        val yamlIntent = buildViewIntent(uri, ProfileFileEditor.MIME_YAML, writable)
        val viewIntent = if (file.isYaml() &&
            yamlIntent.resolveActivity(activity.packageManager) != null
        ) {
            yamlIntent
        } else {
            buildViewIntent(uri, ProfileFileEditor.MIME_TEXT, writable)
        }

        if (viewIntent.resolveActivity(activity.packageManager) == null) {
            throw IllegalStateException(activity.getString(R.string.external_editor_unavailable))
        }

        activity.startActivityForResult(
            ActivityResultContracts.StartActivityForResult(),
            Intent.createChooser(
                viewIntent,
                activity.getString(R.string.open_in_external_editor),
            ),
        )
    }

    suspend fun importEdited(file: File, candidate: Uri) {
        val session = ProfileFileEditor.prepare(
            context = activity,
            original = client.buildDocumentUri(file.id),
            editedSource = candidate,
        )

        try {
            processCandidate(file, session, R.string.edited_configuration_no_changes)
        } finally {
            session.close()
        }
    }

    suspend fun showOutline(file: File) {
        val counts = if (file.size > MAX_OUTLINE_BYTES) {
            ConfigOutline.Counts(malformed = true)
        } else {
            try {
                val yaml = client.readText(file.id)

                withContext(Dispatchers.Default) {
                    ConfigOutline.count(yaml)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConfigOutline.Counts(malformed = true)
            }
        }

        design.showOutline(
            file = file,
            proxies = counts.proxies,
            proxyGroups = counts.proxyGroups,
            rules = counts.rules,
            malformed = counts.malformed,
        )
    }

    private suspend fun openConfigurationEditor(file: File) {
        val session = ProfileFileEditor.prepare(activity, client.buildDocumentUri(file.id))

        try {
            activity.startActivityForResult(
                ActivityResultContracts.StartActivityForResult(),
                session.createEditIntent(activity),
            )
            processCandidate(file, session, R.string.external_editor_no_changes)
        } finally {
            session.close()
        }
    }

    private suspend fun processCandidate(
        file: File,
        session: ProfileFileEditor,
        @StringRes noChangesMessage: Int,
    ) {
        if (!session.hasChanges()) {
            design.showToast(noChangesMessage, ToastDuration.Long)
            return
        }

        when (val result = ProfileFileRoundTrip.validateAndStage(uuid, file.id, client, session)) {
            ProfileFileRoundTrip.Result.Staged -> {
                design.showToast(R.string.edited_configuration_staged, ToastDuration.Long)
            }
            is ProfileFileRoundTrip.Result.Rejected -> {
                val reason = result.cause.message ?: activity.getString(R.string.error)
                val message = if (result.rollbackFailure == null) {
                    activity.getString(R.string.edited_configuration_rejected, reason)
                } else {
                    activity.getString(R.string.edited_configuration_rollback_failed, reason)
                }

                design.showExceptionToast(message)
            }
        }
    }

    private fun buildViewIntent(uri: Uri, mimeType: String, writable: Boolean): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            clipData = ClipData.newRawUri("profile file", uri)
            grantPermissions(read = true, write = writable)
        }
    }

    private fun File.isYaml(): Boolean {
        return name.endsWith(".yaml", ignoreCase = true) ||
            name.endsWith(".yml", ignoreCase = true)
    }

    private companion object {
        const val MAX_OUTLINE_BYTES = 4L * 1024L * 1024L
    }
}
