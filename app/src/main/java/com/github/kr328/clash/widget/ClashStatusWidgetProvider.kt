package com.github.kr328.clash.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.github.kr328.clash.R

/** 2×1 status widget: running indicator, profile, compact rates, toggle. */
class ClashStatusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetUiBinder.updateIds(
            context,
            appWidgetManager,
            appWidgetIds,
            R.layout.widget_clash_status,
            force = true,
        )
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetUiBinder.forgetIds(appWidgetIds)
    }
}
