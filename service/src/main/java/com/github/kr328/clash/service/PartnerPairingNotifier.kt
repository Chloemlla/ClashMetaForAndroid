package com.github.kr328.clash.service

import android.app.ActivityManager
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Asks the device owner to confirm a partner app that has not been answered about yet.
 *
 * The prompt is raised at most once per (package, certificate) until it is answered, so a partner
 * polling status on a timer cannot turn into a notification loop. A notification the owner
 * dismissed is re-raised so the entry point is not lost forever, but never more often than once per
 * [PROMPT_COOLDOWN_MILLIS] (B-176).
 */
object PartnerPairingNotifier {
    const val EXTRA_PACKAGE = "partner_package"
    const val EXTRA_SHA256 = "partner_sha256"

    private const val CHANNEL_ID = "partner_pairing"
    private const val PROMPT_COOLDOWN_MILLIS = 60_000L

    private val lastPromptAtMillis = ConcurrentHashMap<String, Long>()

    fun request(context: Context, packageName: String, digests: PartnerSignerDigests) {
        val now = System.currentTimeMillis()
        if (now - (lastPromptAtMillis[packageName] ?: 0L) < PROMPT_COOLDOWN_MILLIS) {
            return
        }

        val store = PartnerGrantStore(context)
        val isNew = store.requestPairing(packageName, digests.sha256)
        val stillVisible = notificationVisible(context, packageName)
        if (!isNew && stillVisible) {
            return
        }

        lastPromptAtMillis[packageName] = now
        showPrompt(context, packageName, digests)
    }

    private fun showPrompt(context: Context, packageName: String, digests: PartnerSignerDigests) {
        val intent = pairingIntent(packageName, digests.sha256)

        // While CMFA is in the foreground the dialog can be raised right away, which is the
        // point of the feature. A background start is refused by the platform, and then the
        // notification below is the only entry point — never a crash (B-174).
        if (isAppInForeground(context)) {
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                Log.d("PartnerPairing: dialog deferred to notification for $packageName", it)
            }
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

        // B-177: the unforgeable package name is shown next to the app's own label, so the owner
        // is not making a trust decision on a string the caller controls.
        val text = context.getString(
            R.string.format_partner_pairing_request,
            "${labelOf(context, packageName)} ($packageName)",
            digests.sha256.take(8),
        )
        val id = notificationId(packageName)
        val contentIntent = PendingIntent.getActivity(
            context,
            id,
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

        // Per-package id so a second pending partner no longer replaces the first one's prompt (B-176).
        context.notifyIfAllowed(id, notification)
    }

    private fun notificationId(packageName: String): Int = packageName.hashCode()

    /**
     * Removes the prompt notification for [packageName]. The id is derived from the package (B-176),
     * so only the notifier can compute it — the pairing UI has to ask for the dismissal.
     */
    fun dismiss(context: Context, packageName: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(packageName))
    }

    private fun notificationVisible(context: Context, packageName: String): Boolean =
        NotificationManagerCompat.from(context).activeNotifications
            .any { it.id == notificationId(packageName) }

    private fun isAppInForeground(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.runningAppProcesses
            ?.any {
                it.processName == context.packageName &&
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }
            ?: false
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
