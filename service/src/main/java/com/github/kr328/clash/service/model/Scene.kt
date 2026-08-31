package com.github.kr328.clash.service.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Scene(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
    val priority: Int = 0,
    val trigger: SceneTrigger = SceneTrigger(),
    val action: SceneAction = SceneAction(),
)

@Serializable
data class SceneTrigger(
    val networkType: SceneNetworkType = SceneNetworkType.Any,
    val timeWindow: SceneTimeWindow? = null,
    val ssid: String? = null,
)

// Names are pinned to what is already stored in SharedPreferences, so a constant may be renamed
// without invalidating saved scenes.
@Serializable
enum class SceneNetworkType {
    @SerialName("UnmeteredWifi")
    UnmeteredWifi,

    @SerialName("Metered")
    Metered,

    @SerialName("Any")
    Any,
}

@Serializable
data class SceneTimeWindow(
    val startMinute: Int,
    val endMinute: Int,
    val days: Set<Int> = ALL_DAYS,
) {
    companion object {
        val ALL_DAYS: Set<Int> = (1..7).toSet()
    }
}

@Serializable
data class SceneAction(
    val mode: SceneMode = SceneMode.Rule,
    val profileId: String? = null,
)

@Serializable
enum class SceneMode {
    @SerialName("Direct")
    Direct,

    @SerialName("Global")
    Global,

    @SerialName("Rule")
    Rule,
}

object SceneTemplates {
    const val HOME_DIRECT_ID = "home-direct"
    const val AWAY_PROXY_ID = "away-proxy"

    fun defaults(): List<Scene> = listOf(
        Scene(
            id = HOME_DIRECT_ID,
            name = "Home direct",
            enabled = false,
            priority = 0,
            trigger = SceneTrigger(networkType = SceneNetworkType.UnmeteredWifi),
            action = SceneAction(mode = SceneMode.Direct),
        ),
        Scene(
            id = AWAY_PROXY_ID,
            name = "Away proxy",
            enabled = false,
            priority = 1,
            trigger = SceneTrigger(networkType = SceneNetworkType.Metered),
            action = SceneAction(mode = SceneMode.Rule),
        ),
    )
}
