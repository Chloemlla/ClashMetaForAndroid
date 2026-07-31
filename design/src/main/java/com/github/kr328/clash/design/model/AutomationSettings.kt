package com.github.kr328.clash.design.model

data class SceneProfileOption(
    val id: String,
    val name: String,
)

enum class SceneNetworkOption {
    UnmeteredWifi,
    Metered,
    Any,
}

enum class SceneScheduleOption {
    Always,
    Daytime,
    Night,
    Custom,
}

enum class SceneModeOption {
    Direct,
    Global,
    Rule,
}

enum class FailoverSortOption {
    Default,
    Name,
    Delay,
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
