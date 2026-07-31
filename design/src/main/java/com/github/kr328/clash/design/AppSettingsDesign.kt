package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.model.ServiceSettings
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.isDynamicColorAvailable
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class AppSettingsDesign(
    context: Context,
    uiStore: UiStore,
    srvStore: ServiceSettings,
    behavior: Behavior,
    running: Boolean,
    onHideIconChange: (hide: Boolean) -> Unit,
) : Design<AppSettingsDesign.Request>(context) {
    enum class Request {
        ReCreateAllActivities
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            category(R.string.behavior)

            switch(
                value = behavior::autoRestart,
                icon = R.drawable.ic_baseline_restore,
                title = R.string.auto_restart,
                summary = R.string.allow_clash_auto_restart,
            )

            category(R.string.interface_)

            selectableList(
                value = uiStore::darkMode,
                values = DarkMode.values(),
                valuesText = arrayOf(
                    R.string.follow_system_android_10,
                    R.string.always_light,
                    R.string.always_dark
                ),
                icon = R.drawable.ic_baseline_brightness_4,
                title = R.string.dark_mode
            ) {
                listener = OnChangedListener {
                    requests.trySend(Request.ReCreateAllActivities)
                }
            }

            if (isDynamicColorAvailable()) {
                switch(
                    value = uiStore::dynamicColors,
                    icon = R.drawable.ic_baseline_brightness_4,
                    title = R.string.dynamic_colors_title,
                    summary = R.string.dynamic_colors_summary,
                ) {
                    listener = OnChangedListener {
                        requests.trySend(Request.ReCreateAllActivities)
                    }
                }
            }

            switch(
                value = uiStore::hideAppIcon,
                icon = R.drawable.ic_baseline_hide,
                title = R.string.hide_app_icon_title,
                summary = R.string.hide_app_icon_desc,
            ) {
                listener = OnChangedListener {
                    onHideIconChange(uiStore::hideAppIcon.get())
                }
            }

            switch(
                value = uiStore::hideFromRecents,
                icon = R.drawable.ic_baseline_stack,
                title = R.string.hide_from_recents_title,
                summary = R.string.hide_from_recents_desc,
            ) {
                listener = OnChangedListener {
                    requests.trySend(Request.ReCreateAllActivities)
                }
            }

            category(R.string.privacy)

            switch(
                value = uiStore::appLockEnabled,
                icon = R.drawable.ic_baseline_vpn_lock,
                title = R.string.app_lock_title,
                summary = R.string.app_lock_desc,
            ) {
                listener = OnChangedListener {
                    // Toggling off must drop any stale unlock timestamp so re-enabling later
                    // requires a fresh authentication rather than reusing an old lastUnlockedAt.
                    if (!uiStore.appLockEnabled) {
                        uiStore.lastUnlockedAt = 0L
                    }
                }
            }

            switch(
                value = uiStore::secureScreen,
                icon = R.drawable.ic_baseline_visibility_off,
                title = R.string.secure_screen_title,
                summary = R.string.secure_screen_desc,
            ) {
                listener = OnChangedListener {
                    requests.trySend(Request.ReCreateAllActivities)
                }
            }

            category(R.string.service)

            switch(
                value = srvStore::dynamicNotification,
                icon = R.drawable.ic_baseline_domain,
                title = R.string.show_traffic,
                summary = R.string.show_traffic_summary
            ) {
                enabled = !running
            }

            switch(
                value = srvStore::localSubscriptionTraffic,
                icon = R.drawable.ic_baseline_domain,
                title = R.string.local_subscription_traffic_title,
                summary = R.string.local_subscription_traffic_summary,
            )

            switch(
                value = srvStore::subscriptionExpiryReminders,
                icon = R.drawable.ic_outline_update,
                title = R.string.subscription_expiry_reminders_title,
                summary = R.string.subscription_expiry_reminders_summary,
            )
        }

        binding.content.addView(screen.root)
    }
}
