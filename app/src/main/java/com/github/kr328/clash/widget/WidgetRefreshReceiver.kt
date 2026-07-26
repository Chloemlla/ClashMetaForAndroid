package com.github.kr328.clash.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.service.store.WidgetStateStore

/**
 * Non-exported observer for Clash self-broadcasts.
 * Keeps AppWidgetProvider export surface limited to APPWIDGET_UPDATE.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        // Self-broadcasts are package-targeted; still ignore unexpected actions.
        when (action) {
            Intents.ACTION_CLASH_STARTED,
            Intents.ACTION_CLASH_STOPPED,
            Intents.ACTION_PROFILE_LOADED,
            Intents.ACTION_SERVICE_RECREATED,
            WidgetStateStore.ACTION_WIDGET_STATE_CHANGED,
            // Skip-if-unchanged: traffic ticks can fire often; only redraw when UI model differs.
            -> WidgetUiBinder.updateAll(context.applicationContext, force = false)
        }
    }
}
