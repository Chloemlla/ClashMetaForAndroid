package com.github.kr328.clash.service.scene

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
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.model.Scene
import com.github.kr328.clash.service.model.SceneTemplates
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.notifyIfAllowed

object AutomationNotifier {
    private const val CHANNEL_ID = "automation_events"

    fun notifyScene(context: Context, scene: Scene) {
        if (!ServiceStore(context).sceneNotificationsEnabled) return

        post(
            context = context,
            title = context.getString(
                R.string.format_scene_applied,
                scene.localizedName(context),
            ),
            text = context.getString(R.string.scene_applied_summary),
        )
    }

    fun notifyFailover(context: Context, group: String, from: String, to: String) {
        if (!ServiceStore(context).failoverNotificationsEnabled) return

        post(
            context = context,
            title = context.getString(R.string.proxy_failover_applied),
            text = context.getString(R.string.format_proxy_failover, group, from, to),
        )
    }

    private fun post(context: Context, title: String, text: String) {
        ensureChannel(context)
        val id = UndefinedIds.next()
        val contentIntent = PendingIntent.getActivity(
            context,
            id,
            Intent().setComponent(Components.MAIN_ACTIVITY),
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
                ).setName(context.getString(R.string.automation_notification_channel)).build(),
            ),
        )
    }

    private fun Scene.localizedName(context: Context): String = when (id) {
        SceneTemplates.HOME_DIRECT_ID -> context.getString(R.string.service_scene_home_direct)
        SceneTemplates.AWAY_PROXY_ID -> context.getString(R.string.service_scene_away_proxy)
        else -> name
    }
}
