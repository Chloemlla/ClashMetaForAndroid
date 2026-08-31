package com.github.kr328.clash.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.github.kr328.clash.InternalControlActivity
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.ProfilesActivity
import com.github.kr328.clash.ProxyActivity
import com.github.kr328.clash.R
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.R as ServiceR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Run a widget refresh off the main thread, holding the broadcast alive until it finishes.
 *
 * [WidgetUiBinder.buildModel] queries StatusProvider, which may cold-start the `:background`
 * process. Doing that inline in `onReceive` / `onUpdate` blocks the main thread for the whole cold
 * start against a 10 s receiver budget.
 */
internal fun BroadcastReceiver.runWidgetUpdateAsync(block: () -> Unit) {
    val pending = goAsync()

    WidgetUiBinder.scope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/**
 * Builds [WidgetUiModel] and binds RemoteViews for status / control widgets.
 *
 * StatusProvider is the only read channel: the widget code runs in the UI process while the
 * live state lives in `:background`, so there is nothing in-process to fall back to.
 * Missing rates show "—".
 */
object WidgetUiBinder {
    // Updates run on a background dispatcher (see runWidgetUpdateAsync) and several receivers can
    // land at once, so this cannot be a plain map.
    private val lastModels = ConcurrentHashMap<Int, WidgetUiModel>()

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun buildModel(context: Context): WidgetUiModel {
        val snapshot = StatusClient(context).widgetState()

        val running = snapshot?.running ?: false
        val profileRaw = snapshot?.profileName
        val profileName = if (!profileRaw.isNullOrBlank()) {
            WidgetFormat.truncateProfile(profileRaw)
        } else {
            context.getString(ServiceR.string.profile_not_selected)
        }

        val mode = snapshot?.mode?.takeIf { it.isNotBlank() } ?: "—"

        val selectedNodeRaw = snapshot?.selectedNode?.takeIf { it.isNotBlank() }
        val selectedNode = selectedNodeRaw?.let(WidgetFormat::truncateNode) ?: "—"

        val hasDetail = snapshot?.hasDetail == true
        val up = if (hasDetail) snapshot?.upRateBytesPerSec else null
        val down = if (hasDetail) snapshot?.downRateBytesPerSec else null
        val hasRates = up != null && down != null
        val ratesText = if (up != null && down != null) {
            WidgetFormat.ratesLine(up, down)
        } else {
            "—"
        }

        return WidgetUiModel(
            running = running,
            profileName = profileName,
            selectedNode = selectedNode,
            mode = mode,
            ratesText = ratesText,
            hasRates = hasRates,
        )
    }

    fun updateAll(context: Context, force: Boolean = false) {
        val manager = AppWidgetManager.getInstance(context)
        updateProvider(context, manager, ClashStatusWidgetProvider::class.java, force)
        updateProvider(context, manager, ClashControlWidgetProvider::class.java, force)
    }

    fun updateIds(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
        layoutId: Int,
        force: Boolean = false,
    ) {
        if (appWidgetIds.isEmpty()) return
        val model = buildModel(context)
        for (id in appWidgetIds) {
            if (!force && model == lastModels[id]) continue
            val views = RemoteViews(context.packageName, layoutId)
            bind(context, views, model, layoutId)
            manager.updateAppWidget(id, views)
            // Recorded only after the panel actually took the update: with updatePeriodMillis at
            // 30 minutes, marking a failed push as delivered pins stale state for half an hour.
            lastModels[id] = model
        }
    }

    fun forgetIds(appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            lastModels.remove(id)
        }
    }

    private fun updateProvider(
        context: Context,
        manager: AppWidgetManager,
        providerClass: Class<*>,
        force: Boolean,
    ) {
        val ids = manager.getAppWidgetIds(ComponentName(context, providerClass))
        if (ids.isEmpty()) return
        val layoutId = when (providerClass) {
            ClashStatusWidgetProvider::class.java -> R.layout.widget_clash_status
            else -> R.layout.widget_clash_control
        }
        updateIds(context, manager, ids, layoutId, force)
    }

    private fun bind(
        context: Context,
        views: RemoteViews,
        model: WidgetUiModel,
        layoutId: Int,
    ) {
        val statusLabel = if (model.running) {
            context.getString(DesignR.string.running)
        } else {
            context.getString(DesignR.string.stopped)
        }
        val statusColor = if (model.running) {
            ContextCompat.getColor(context, R.color.widget_status_running)
        } else {
            ContextCompat.getColor(context, R.color.widget_status_stopped)
        }

        views.setTextViewText(R.id.widget_status, statusLabel)
        views.setTextColor(R.id.widget_status, statusColor)
        views.setTextViewText(R.id.widget_profile, model.profileName)
        views.setTextViewText(R.id.widget_rates, model.ratesText)
        views.setTextViewText(
            R.id.widget_toggle,
            if (model.running) {
                context.getString(R.string.widget_action_stop)
            } else {
                context.getString(R.string.widget_action_start)
            },
        )

        // Primary body opens Main; toggle is always InternalControl (F-12).
        views.setOnClickPendingIntent(R.id.widget_root, openMainPending(context))
        views.setOnClickPendingIntent(R.id.widget_toggle, togglePending(context))

        if (layoutId == R.layout.widget_clash_control) {
            views.setTextViewText(R.id.widget_node, model.selectedNode)
            views.setTextViewText(R.id.widget_mode, model.mode)
            views.setOnClickPendingIntent(R.id.widget_open_proxies, openProxyPending(context))
            views.setOnClickPendingIntent(R.id.widget_open_profiles, openProfilesPending(context))
        }
    }

    private fun togglePending(context: Context): PendingIntent {
        // Explicit InternalControl only (F-12) — never ExternalControl.
        val intent = Intent(Intents.ACTION_TOGGLE_CLASH).apply {
            component = ComponentName(context, InternalControlActivity::class.java)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQ_TOGGLE,
            intent,
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

    private fun openMainPending(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQ_MAIN,
            intent,
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

    private fun openProxyPending(context: Context): PendingIntent {
        val intent = Intent(context, ProxyActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQ_PROXY,
            intent,
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

    private fun openProfilesPending(context: Context): PendingIntent {
        val intent = Intent(context, ProfilesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQ_PROFILES,
            intent,
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

    private const val REQ_TOGGLE = 4101
    private const val REQ_MAIN = 4102
    private const val REQ_PROXY = 4103
    private const val REQ_PROFILES = 4104
}
