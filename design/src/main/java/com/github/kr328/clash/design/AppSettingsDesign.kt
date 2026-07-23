package com.github.kr328.clash.design

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.model.ServiceSettings
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.launch

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

        lateinit var localTrafficPreference: SwitchPreference

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

            category(R.string.service)

            switch(
                value = srvStore::dynamicNotification,
                icon = R.drawable.ic_baseline_domain,
                title = R.string.show_traffic,
                summary = R.string.show_traffic_summary
            ) {
                enabled = !running
            }

            // Hidden by default; reveal via multi-tap on the settings title.
            localTrafficPreference = switch(
                value = srvStore::localSubscriptionTraffic,
                icon = R.drawable.ic_baseline_domain,
                title = R.string.local_subscription_traffic_title,
                summary = R.string.local_subscription_traffic_summary,
            )
        }

        binding.content.addView(screen.root)

        val revealed = uiStore.revealLocalSubscriptionTrafficSetting
        localTrafficPreference.view.visibility = if (revealed) View.VISIBLE else View.GONE

        installHiddenUnlock(uiStore, localTrafficPreference)
    }

    private fun installHiddenUnlock(
        uiStore: UiStore,
        localTrafficPreference: SwitchPreference,
    ) {
        val titleView = binding.activityBarLayout
            .findViewById<TextView>(R.id.activity_bar_title_view)
            ?: return

        var tapCount = 0
        var lastTapAt = 0L

        titleView.isClickable = true
        titleView.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTapAt > 1_500L) {
                tapCount = 0
            }
            lastTapAt = now
            tapCount += 1

            if (tapCount < 7) {
                return@setOnClickListener
            }

            tapCount = 0
            if (uiStore.revealLocalSubscriptionTrafficSetting &&
                localTrafficPreference.view.visibility == View.VISIBLE
            ) {
                return@setOnClickListener
            }

            uiStore.revealLocalSubscriptionTrafficSetting = true
            localTrafficPreference.view.visibility = View.VISIBLE
            launch {
                showToast(
                    R.string.local_subscription_traffic_unlocked,
                    ToastDuration.Short,
                )
            }
        }
    }
}