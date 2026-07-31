package com.github.kr328.clash.store

import android.content.Context
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.AutomationSettings
import com.github.kr328.clash.design.model.FailoverSortOption
import com.github.kr328.clash.design.model.SceneModeOption
import com.github.kr328.clash.design.model.SceneNetworkOption
import com.github.kr328.clash.design.model.SceneScheduleOption
import com.github.kr328.clash.design.model.SceneSetting
import com.github.kr328.clash.service.model.Scene
import com.github.kr328.clash.service.model.SceneMode
import com.github.kr328.clash.service.model.SceneNetworkType
import com.github.kr328.clash.service.model.SceneTemplates
import com.github.kr328.clash.service.model.SceneTimeWindow
import com.github.kr328.clash.service.scene.SceneStore
import com.github.kr328.clash.service.scene.normalizeFailoverCooldownMillis
import com.github.kr328.clash.service.scene.normalizeFailoverThreshold
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendAutomationChanged

class AutomationSettingsAdapter(private val context: Context) : AutomationSettings {
    private val store = ServiceStore(context)
    private val sceneStore = SceneStore(context)

    override var autoScenesEnabled: Boolean
        get() = store.autoScenesEnabled
        set(value) = updateSetting { store.autoScenesEnabled = value }

    override var sceneNotificationsEnabled: Boolean
        get() = store.sceneNotificationsEnabled
        set(value) = updateSetting { store.sceneNotificationsEnabled = value }

    override var sceneSsidMatchingEnabled: Boolean
        get() = store.sceneSsidMatchingEnabled
        set(value) = updateSetting { store.sceneSsidMatchingEnabled = value }

    override var autoFailoverEnabled: Boolean
        get() = store.autoFailoverEnabled
        set(value) = updateSetting { store.autoFailoverEnabled = value }

    override var failoverNotificationsEnabled: Boolean
        get() = store.failoverNotificationsEnabled
        set(value) = updateSetting { store.failoverNotificationsEnabled = value }

    override var failoverThreshold: Int
        get() = normalizeFailoverThreshold(store.failoverThreshold)
        set(value) = updateSetting { store.failoverThreshold = normalizeFailoverThreshold(value) }

    override var failoverCooldownSeconds: Int
        get() = (normalizeFailoverCooldownMillis(store.failoverCooldownMillis) / 1_000L).toInt()
        set(value) = updateSetting {
            store.failoverCooldownMillis = normalizeFailoverCooldownMillis(value.toLong() * 1_000L)
        }

    override var failoverSort: FailoverSortOption
        get() = when (store.failoverSort) {
            ProxySort.Default -> FailoverSortOption.Default
            ProxySort.Title -> FailoverSortOption.Name
            ProxySort.Delay -> FailoverSortOption.Delay
        }
        set(value) = updateSetting {
            store.failoverSort = when (value) {
                FailoverSortOption.Default -> ProxySort.Default
                FailoverSortOption.Name -> ProxySort.Title
                FailoverSortOption.Delay -> ProxySort.Delay
            }
        }

    override val scenes: List<SceneSetting>
        get() = sceneStore.scenes.map(::SceneSettingAdapter)

    override fun addMissingTemplates() {
        sceneStore.addMissingTemplates()
        context.sendAutomationChanged()
    }

    override fun moveScene(id: String, offset: Int) {
        sceneStore.move(id, offset)
        context.sendAutomationChanged()
    }

    private fun updateSetting(update: () -> Unit) {
        update()
        context.sendAutomationChanged()
    }

    private inner class SceneSettingAdapter(private val fallback: Scene) : SceneSetting {
        override val id: String = fallback.id

        override val name: String
            get() = when (id) {
                SceneTemplates.HOME_DIRECT_ID -> context.getString(R.string.scene_home_direct)
                SceneTemplates.AWAY_PROXY_ID -> context.getString(R.string.scene_away_proxy)
                else -> current().name
            }

        override var enabled: Boolean
            get() = current().enabled
            set(value) = update { copy(enabled = value) }

        override var network: SceneNetworkOption
            get() = when (current().trigger.networkType) {
                SceneNetworkType.UnmeteredWifi -> SceneNetworkOption.UnmeteredWifi
                SceneNetworkType.Metered -> SceneNetworkOption.Metered
                SceneNetworkType.Any -> SceneNetworkOption.Any
            }
            set(value) = update {
                copy(
                    trigger = trigger.copy(
                        networkType = when (value) {
                            SceneNetworkOption.UnmeteredWifi -> SceneNetworkType.UnmeteredWifi
                            SceneNetworkOption.Metered -> SceneNetworkType.Metered
                            SceneNetworkOption.Any -> SceneNetworkType.Any
                        },
                    ),
                )
            }

        override var schedule: SceneScheduleOption
            get() = current().trigger.timeWindow.toSchedule()
            set(value) = update {
                if (value == SceneScheduleOption.Custom) {
                    this
                } else {
                    copy(
                        trigger = trigger.copy(
                            timeWindow = when (value) {
                                SceneScheduleOption.Always -> null
                                SceneScheduleOption.Daytime -> DAYTIME
                                SceneScheduleOption.Night -> NIGHT
                                SceneScheduleOption.Custom -> trigger.timeWindow
                            },
                        ),
                    )
                }
            }

        override var mode: SceneModeOption
            get() = when (current().action.mode) {
                SceneMode.Direct -> SceneModeOption.Direct
                SceneMode.Global -> SceneModeOption.Global
                SceneMode.Rule -> SceneModeOption.Rule
            }
            set(value) = update {
                copy(
                    action = action.copy(
                        mode = when (value) {
                            SceneModeOption.Direct -> SceneMode.Direct
                            SceneModeOption.Global -> SceneMode.Global
                            SceneModeOption.Rule -> SceneMode.Rule
                        },
                    ),
                )
            }

        override var profileId: String
            get() = current().action.profileId.orEmpty()
            set(value) = update {
                copy(action = action.copy(profileId = value.takeIf { it.isNotBlank() }))
            }

        override var ssid: String?
            get() = current().trigger.ssid
            set(value) = update {
                copy(trigger = trigger.copy(ssid = value?.trim()?.takeIf { it.isNotEmpty() }))
            }

        private fun current(): Scene {
            return sceneStore.scenes.firstOrNull { it.id == id } ?: fallback
        }

        private fun update(transform: Scene.() -> Scene) {
            sceneStore.update(current().transform())
            context.sendAutomationChanged()
        }
    }

    private fun SceneTimeWindow?.toSchedule(): SceneScheduleOption = when (this) {
        null -> SceneScheduleOption.Always
        DAYTIME -> SceneScheduleOption.Daytime
        NIGHT -> SceneScheduleOption.Night
        else -> SceneScheduleOption.Custom
    }

    private companion object {
        val DAYTIME = SceneTimeWindow(
            startMinute = 7 * 60,
            endMinute = 19 * 60,
        )
        val NIGHT = SceneTimeWindow(
            startMinute = 19 * 60,
            endMinute = 7 * 60,
        )
    }
}
