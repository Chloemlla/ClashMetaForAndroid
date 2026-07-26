package com.github.kr328.clash.service.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.id.UndefinedIds
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import java.util.UUID

/**
 * Local subscription-expiry reminders. User-toggleable; once per profile per
 * (bucket, expire) key stored in [ServiceStore].
 */
object SubscriptionExpiryNotifier {
    private const val CHANNEL_ID = "subscription_expiry_channel"

    suspend fun checkAll(context: Context) {
        val store = ServiceStore(context)
        if (!store.subscriptionExpiryReminders) return

        ensureChannel(context)

        val now = System.currentTimeMillis()
        var notified = store.subscriptionExpiryNotifiedKeys
        var changed = false

        ImportedDao().queryAll()
            .filter { it.type == Profile.Type.Url && it.expire > 0L }
            .forEach { imported ->
                val key = evaluateAndNotify(context, imported, now, notified) ?: return@forEach
                notified = SubscriptionExpiry.markNotified(notified, key)
                changed = true
            }

        if (changed) {
            store.subscriptionExpiryNotifiedKeys = notified
        }
    }

    suspend fun checkProfile(context: Context, uuid: UUID) {
        val store = ServiceStore(context)
        if (!store.subscriptionExpiryReminders) return

        val imported = ImportedDao().queryByUUID(uuid) ?: return
        if (imported.type != Profile.Type.Url || imported.expire <= 0L) return

        ensureChannel(context)

        val now = System.currentTimeMillis()
        val notified = store.subscriptionExpiryNotifiedKeys
        val key = evaluateAndNotify(context, imported, now, notified) ?: return
        store.subscriptionExpiryNotifiedKeys = SubscriptionExpiry.markNotified(notified, key)
    }

    private fun evaluateAndNotify(
        context: Context,
        imported: Imported,
        nowMs: Long,
        lastNotified: Set<String>,
    ): String? {
        val bucket = SubscriptionExpiry.evaluate(imported.expire, nowMs)
        val key = SubscriptionExpiry.notificationKey(imported.uuid, bucket, imported.expire)
        if (!SubscriptionExpiry.shouldNotify(lastNotified, key) || key == null) {
            return null
        }

        postNotification(context, imported, bucket)
        return key
    }

    private fun postNotification(
        context: Context,
        imported: Imported,
        bucket: SubscriptionExpiry.Bucket,
    ) {
        val id = UndefinedIds.next()
        val title: String
        val text: String
        when (bucket) {
            SubscriptionExpiry.Bucket.Expired -> {
                title = context.getString(R.string.subscription_expired_title)
                text = context.getString(R.string.format_subscription_expired, imported.name)
            }
            SubscriptionExpiry.Bucket.ExpiringSoon -> {
                title = context.getString(R.string.subscription_expiring_title)
                text = context.getString(R.string.format_subscription_expiring, imported.name)
            }
            SubscriptionExpiry.Bucket.None -> return
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            id,
            Intent().setComponent(Components.PROPERTIES_ACTIVITY).setUUID(imported.uuid),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_service)
            .setColor(context.getColorCompat(R.color.color_clash))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(CHANNEL_ID)
            .build()

        context.notifyIfAllowed(id, notification)
    }

    private fun ensureChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_LOW,
                ).setName(context.getString(R.string.subscription_expiry_channel)).build()
            )
        )
    }
}
