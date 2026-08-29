package com.github.kr328.clash.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.PartnerSignerDigests
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.store.PartnerGrantStore
import com.github.kr328.clash.service.util.notifyIfAllowed

/**
 * Asks the device owner to confirm a partner app that has not been answered about yet.
 *
 * The prompt is raised at most once per (package, certificate) until it is answered, so a partner
 * polling status on a timer cannot turn into a notification loop.
 */
object PartnerPairingNotifier {
    const val EXTRA_PACKAGE = "partner_package"
    const val EXTRA_SHA256 = "partner_sha256"

    private const val CHANNEL_ID = "partner_pairing"

    fun request(context: Context, packageName: String, digests: PartnerSignerDigests) {
        if (!PartnerGrantStore(context).requestPairing(packageName, digests.sha256)) {
            return
        }

        val intent = pairingIntent(packageName, digests.sha256)

        // While CMFA is in the foreground the dialog can be raised right away, which is the
        // point of the feature. A background start is refused by the platform, and then the
        // notification below is the only entry point — never a crash.
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Log.d("PartnerPairing: dialog deferred to notification for $packageName", it)
        }

        notify(context, packageName, digests, intent)
    }

    fun pairingIntent(packageName: String, sha256: String): Intent =
        Intent()
            .setComponent(Components.PARTNER_PAIRING_ACTIVITY)
            .putExtra(EXTRA_PACKAGE, packageName)
            .putExtra(EXTRA_SHA256, sha256)

    private fun notify(
        context: Context,
        packageName: String,
        digests: PartnerSignerDigests,
        intent: Intent,
    ) {
        ensureChannel(context)

        val text = context.getString(
            R.string.format_partner_pairing_request,
            labelOf(context, packageName),
            digests.sha256.take(8),
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            intent,
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_service)
            .setColor(context.getColorCompat(R.color.color_clash))
            .setContentTitle(context.getString(R.string.partner_pairing_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        context.notifyIfAllowed(R.id.nf_partner_pairing, notification)
    }

    private fun labelOf(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun ensureChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_HIGH,
                ).setName(context.getString(R.string.partner_pairing_channel)).build(),
            ),
        )
    }
}
