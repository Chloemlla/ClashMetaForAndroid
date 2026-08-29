package com.github.kr328.clash.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.Profile
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.ProfileShareUri
import com.github.kr328.clash.design.util.QrBitmap
import com.github.kr328.clash.design.util.showExceptionToast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * User-initiated QR export for URL profiles (not auto-backup; F-17).
 */
object ProfileQrExport {
    suspend fun show(design: Design<*>, profile: Profile) {
        val context = design.context
        if (profile.type != Profile.Type.Url) {
            design.showToast(R.string.export_qr_unavailable, ToastDuration.Long)
            return
        }

        withContext(Dispatchers.Main) {
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_profile_qr, null, false)
            view.findViewById<TextView>(R.id.title_view).text = profile.name
            val qrView = view.findViewById<ImageView>(R.id.qr_view)
            val includeName = view.findViewById<CheckBox>(R.id.include_name_check).apply {
                isEnabled = profile.name.isNotBlank()
            }

            var currentBitmap: Bitmap? = null
            var encodeJob: Job? = null

            fun updateQr() {
                encodeJob?.cancel()
                val payload = ProfileShareUri.qrPayload(
                    profile.source,
                    if (includeName.isChecked) profile.name else null,
                )
                if (payload == null) {
                    currentBitmap = null
                    qrView.setImageBitmap(null)
                    return
                }

                encodeJob = design.launch(Dispatchers.Main) {
                    val bitmap = try {
                        withContext(Dispatchers.Default) { QrBitmap.encode(payload) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        design.showExceptionToast(e)
                        null
                    }
                    currentBitmap = bitmap
                    qrView.setImageBitmap(bitmap)
                }
            }

            includeName.setOnCheckedChangeListener { _, _ -> updateQr() }
            updateQr()

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.export_qr)
                .setView(view)
                .setPositiveButton(R.string.export_qr_share_image) { _, _ ->
                    currentBitmap?.let { shareBitmap(context, design, profile.name, it) }
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    private fun shareBitmap(
        context: Context,
        design: Design<*>,
        profileName: String,
        bitmap: Bitmap,
    ) {
        try {
            val cacheDir = File(context.cacheDir, "profile-qr").apply { mkdirs() }
            val safeName = profileName
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .ifBlank { "profile" }
                .take(40)
            val file = File(cacheDir, "qr-$safeName-${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException(context.getString(R.string.export_qr_failed))
                }
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )

            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, profileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(
                Intent.createChooser(send, context.getString(R.string.share))
            )
        } catch (e: Exception) {
            design.launch {
                design.showExceptionToast(e)
            }
        }
    }
}
