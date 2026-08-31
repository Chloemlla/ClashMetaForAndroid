package com.github.kr328.clash.design.model

import androidx.annotation.StringRes
import com.github.kr328.clash.design.R

data class SceneProfileOption(
    val id: String,
    val name: String,
)

enum class SceneNetworkOption(@StringRes val labelRes: Int) {
    UnmeteredWifi(R.string.unmetered_wifi),
    Metered(R.string.metered_or_cellular),
    Any(R.string.any_network),
}

enum class SceneScheduleOption(@StringRes val labelRes: Int) {
    Always(R.string.scene_schedule_always),
    Daytime(R.string.scene_schedule_daytime),
    Night(R.string.scene_schedule_night),
    Custom(R.string.scene_schedule_custom),
}

enum class SceneModeOption(@StringRes val labelRes: Int) {
    Direct(R.string.direct_mode),
    Global(R.string.global_mode),
    Rule(R.string.rule_mode),
}

enum class FailoverSortOption(@StringRes val labelRes: Int) {
    Default(R.string.default_),
    Name(R.string.name),
    Delay(R.string.delay),
}

interface SceneSetting {
    val id: String
    val name: String

    var enabled: Boolean
    var network: SceneNetworkOption
    var schedule: SceneScheduleOption
    var mode: SceneModeOption
    var profileId: String
    var ssid: String?
}

interface AutomationSettings {
    var autoScenesEnabled: Boolean
    var sceneNotificationsEnabled: Boolean
    var sceneSsidMatchingEnabled: Boolean

    var autoFailoverEnabled: Boolean
    var failoverNotificationsEnabled: Boolean
    var failoverThreshold: Int
    var failoverCooldownSeconds: Int
    var failoverSort: FailoverSortOption

    val scenes: List<SceneSetting>

    fun addMissingTemplates()
    fun moveScene(id: String, offset: Int)
}
