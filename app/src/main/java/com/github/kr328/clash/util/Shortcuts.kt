package com.github.kr328.clash.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.InternalControlActivity
import com.github.kr328.clash.R
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.design.R as DesignR

/**
 * Publishes the launcher shortcuts, or revokes them when the app icon is hidden.
 *
 * Both call sites must go through here: not publishing is not the same as revoking — shortcuts
 * that were already published stay enumerable by launchers and assistants after the launcher
 * alias is disabled, which defeats the point of hiding the icon.
 */
fun Context.applyDynamicShortcuts(hidden: Boolean) {
    if (hidden) {
        // disableShortcuts also covers copies the user pinned to the home screen, which
        // removeAllDynamicShortcuts leaves behind.
        ShortcutManagerCompat.disableShortcuts(
            this,
            SHORTCUT_IDS,
            getString(DesignR.string.shortcut_disabled_icon_hidden),
        )
        ShortcutManagerCompat.removeAllDynamicShortcuts(this)

        return
    }

    val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
        Intent.FLAG_ACTIVITY_NO_ANIMATION

    fun shortcut(
        id: String,
        shortLabel: Int,
        longLabel: Int,
        icon: Int,
        action: String,
        rank: Int,
    ) = ShortcutInfoCompat.Builder(this, id)
        .setShortLabel(getString(shortLabel))
        .setLongLabel(getString(longLabel))
        .setIcon(IconCompat.createWithResource(this, icon))
        .setIntent(
            Intent(action)
                .setClassName(this, InternalControlActivity::class.java.name)
                .addFlags(flags)
        )
        .setRank(rank)
        .build()

    ShortcutManagerCompat.setDynamicShortcuts(
        this,
        listOf(
            shortcut(
                id = SHORTCUT_TOGGLE,
                shortLabel = DesignR.string.shortcut_toggle_short,
                longLabel = DesignR.string.shortcut_toggle_long,
                icon = R.drawable.ic_toggle_all,
                action = Intents.ACTION_TOGGLE_CLASH,
                rank = 0,
            ),
            shortcut(
                id = SHORTCUT_START,
                shortLabel = DesignR.string.shortcut_start_short,
                longLabel = DesignR.string.shortcut_start_long,
                icon = R.drawable.ic_toggle_on,
                action = Intents.ACTION_START_CLASH,
                rank = 1,
            ),
            shortcut(
                id = SHORTCUT_STOP,
                shortLabel = DesignR.string.shortcut_stop_short,
                longLabel = DesignR.string.shortcut_stop_long,
                icon = R.drawable.ic_toggle_off,
                action = Intents.ACTION_STOP_CLASH,
                rank = 2,
            ),
        )
    )
}

private const val SHORTCUT_TOGGLE = "toggle_clash"
private const val SHORTCUT_START = "start_clash"
private const val SHORTCUT_STOP = "stop_clash"

private val SHORTCUT_IDS = listOf(SHORTCUT_TOGGLE, SHORTCUT_START, SHORTCUT_STOP)
