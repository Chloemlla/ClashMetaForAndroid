package com.github.kr328.clash.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.github.kr328.clash.R

/** 2×2 control widget: status, node, rates, toggle / proxies / profiles. */
class ClashControlWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        runWidgetUpdateAsync {
            WidgetUiBinder.updateIds(
                context,
                appWidgetManager,
                appWidgetIds,
                R.layout.widget_clash_control,
                force = true,
            )
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetUiBinder.forgetIds(appWidgetIds)
    }
}
