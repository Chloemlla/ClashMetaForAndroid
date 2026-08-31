package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.model.AutomationSettings
import com.github.kr328.clash.design.model.FailoverSortOption
import com.github.kr328.clash.design.model.SceneModeOption
import com.github.kr328.clash.design.model.SceneNetworkOption
import com.github.kr328.clash.design.model.SceneProfileOption
import com.github.kr328.clash.design.model.SceneScheduleOption
import com.github.kr328.clash.design.model.SceneSetting
import com.github.kr328.clash.design.preference.NullableTextAdapter
import com.github.kr328.clash.design.preference.OnChangedListener
import com.github.kr328.clash.design.preference.Preference
import com.github.kr328.clash.design.preference.category
import com.github.kr328.clash.design.preference.clickable
import com.github.kr328.clash.design.preference.editableText
import com.github.kr328.clash.design.preference.preferenceScreen
import com.github.kr328.clash.design.preference.selectableList
import com.github.kr328.clash.design.preference.switch
import com.github.kr328.clash.design.preference.tips
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class AutomationSettingsDesign(
    context: Context,
    settings: AutomationSettings,
    profiles: List<SceneProfileOption>,
) : Design<AutomationSettingsDesign.Request>(context) {
    sealed class Request {
        data object AddTemplates : Request()
        data class MoveScene(val id: String, val offset: Int) : Request()
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
            category(R.string.scene_automation)

            switch(
                value = settings::autoScenesEnabled,
                icon = R.drawable.ic_baseline_sync,
                title = R.string.auto_scenes,
                summary = R.string.auto_scenes_summary,
            )

            switch(
                value = settings::sceneNotificationsEnabled,
                title = R.string.scene_notifications,
                summary = R.string.scene_notifications_summary,
            )

            val ssidDependencies = mutableListOf<Preference>()
            val ssidToggle = switch(
                value = settings::sceneSsidMatchingEnabled,
                title = R.string.scene_ssid_matching,
                summary = R.string.scene_ssid_matching_summary,
            ) {
                listener = OnChangedListener {
                    ssidDependencies.forEach {
                        it.enabled = settings.sceneSsidMatchingEnabled
                    }
                }
            }

            tips(R.string.scene_safety_tip)
            tips(R.string.scene_ssid_rationale)

            clickable(
                title = R.string.add_example_scenes,
                summary = R.string.add_example_scenes_summary,
            ) {
                clicked { requests.trySend(Request.AddTemplates) }
            }

            val scenes = settings.scenes
            scenes.forEachIndexed { index, scene ->
                addScene(
                    scene = scene,
                    priority = index + 1,
                    sceneCount = scenes.size,
                    profiles = profiles,
                    ssidDependencies = ssidDependencies,
                )
            }

            ssidToggle.listener?.onChanged()

            category(R.string.proxy_failover)

            val failoverDependencies = mutableListOf<Preference>()
            val failoverToggle = switch(
                value = settings::autoFailoverEnabled,
                icon = R.drawable.ic_baseline_swap_vertical_circle,
                title = R.string.auto_switch_on_failure,
                summary = R.string.auto_switch_on_failure_summary,
            ) {
                listener = OnChangedListener {
                    failoverDependencies.forEach {
                        it.enabled = settings.autoFailoverEnabled
                    }
                }
            }

            switch(
                value = settings::failoverNotificationsEnabled,
                title = R.string.failover_notifications,
                summary = R.string.failover_notifications_summary,
                configure = failoverDependencies::add,
            )

            selectableList(
                value = settings::failoverThreshold,
                values = arrayOf(2, 3, 4, 5),
                valuesText = arrayOf(
                    R.string.two_failures,
                    R.string.three_failures,
                    R.string.four_failures,
                    R.string.five_failures,
                ),
                title = R.string.failover_threshold,
                configure = failoverDependencies::add,
            )

            selectableList(
                value = settings::failoverCooldownSeconds,
                values = arrayOf(30, 60, 120, 300),
                valuesText = arrayOf(
                    R.string.thirty_seconds,
                    R.string.sixty_seconds,
                    R.string.two_minutes,
                    R.string.five_minutes,
                ),
                title = R.string.failover_cooldown,
                configure = failoverDependencies::add,
            )

            selectableList(
                value = settings::failoverSort,
                values = FailoverSortOption.values(),
                valuesText = FailoverSortOption.values().map { context.getString(it.labelRes) },
                title = R.string.failover_candidate_order,
                configure = failoverDependencies::add,
            )

            tips(R.string.failover_tip) {
                failoverDependencies.add(this)
            }

            failoverToggle.listener?.onChanged()
        }

        binding.content.addView(screen.root)
    }

    private fun com.github.kr328.clash.design.preference.PreferenceScreen.addScene(
        scene: SceneSetting,
        priority: Int,
        sceneCount: Int,
        profiles: List<SceneProfileOption>,
        ssidDependencies: MutableList<Preference>,
    ) {
        // Collapse each scene to a summary row; its detail controls are only constructed on
        // first expand, so a setup with many scenes does not inflate every control up front.
        val detailsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        var detailsBuilt = false

        clickable(title = scene.name) {
            clicked {
                if (!detailsBuilt) {
                    val details = preferenceScreen(context) {
                        addSceneDetails(
                            scene = scene,
                            priority = priority,
                            sceneCount = sceneCount,
                            profiles = profiles,
                            ssidDependencies = ssidDependencies,
                        )
                    }
                    detailsContainer.addView(
                        details.root,
                        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
                    )
                    detailsBuilt = true
                }
                detailsContainer.visibility =
                    if (detailsContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        switch(value = scene::enabled) {
            title = context.getString(R.string.scene_enabled)
            summary = context.getString(
                R.string.format_scene_summary,
                priority,
                context.getString(scene.network.labelRes),
                context.getString(scene.mode.labelRes),
            )
        }

        root.addView(detailsContainer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun com.github.kr328.clash.design.preference.PreferenceScreen.addSceneDetails(
        scene: SceneSetting,
        priority: Int,
        sceneCount: Int,
        profiles: List<SceneProfileOption>,
        ssidDependencies: MutableList<Preference>,
    ) {
        selectableList(
            value = scene::network,
            values = SceneNetworkOption.values(),
            valuesText = SceneNetworkOption.values().map { context.getString(it.labelRes) },
            title = R.string.scene_network_trigger,
        )

        val scheduleValues = if (scene.schedule == SceneScheduleOption.Custom) {
            SceneScheduleOption.values()
        } else {
            arrayOf(
                SceneScheduleOption.Always,
                SceneScheduleOption.Daytime,
                SceneScheduleOption.Night,
            )
        }
        selectableList(
            value = scene::schedule,
            values = scheduleValues,
            valuesText = scheduleValues.map { context.getString(it.labelRes) },
            title = R.string.scene_time_window,
        )

        selectableList(
            value = scene::mode,
            values = SceneModeOption.values(),
            valuesText = SceneModeOption.values().map { context.getString(it.labelRes) },
            title = R.string.scene_mode_action,
        )

        val profileValues = buildList {
            add("")
            addAll(profiles.map { it.id })
            if (scene.profileId.isNotBlank() && scene.profileId !in this) {
                add(scene.profileId)
            }
        }.toTypedArray()
        val profileLabels = buildList<CharSequence> {
            add(context.getText(R.string.keep_active_profile))
            addAll(profiles.map { it.name })
            if (profileValues.size > profiles.size + 1) {
                add(context.getString(R.string.format_missing_profile, scene.profileId))
            }
        }
        selectableList(
            value = scene::profileId,
            values = profileValues,
            valuesText = profileLabels,
            title = R.string.scene_profile_action,
        )

        editableText(
            value = scene::ssid,
            adapter = NullableTextAdapter.String,
            title = R.string.scene_ssid,
            placeholder = R.string.scene_ssid_not_set,
        ) {
            ssidDependencies.add(this)
            // This scene is built lazily, after the global ssid-toggle listener has already run;
            // apply the current global state so the field is disabled when matching is off.
            enabled = settings.sceneSsidMatchingEnabled
        }

        if (sceneCount > 1) {
            clickable(
                title = R.string.move_scene_up,
                summary = R.string.move_scene_up_summary,
            ) {
                clicked { requests.trySend(Request.MoveScene(scene.id, -1)) }
                enabled = priority > 1
            }
            clickable(
                title = R.string.move_scene_down,
                summary = R.string.move_scene_down_summary,
            ) {
                clicked { requests.trySend(Request.MoveScene(scene.id, 1)) }
                enabled = priority < sceneCount
            }
        }
    }
}
