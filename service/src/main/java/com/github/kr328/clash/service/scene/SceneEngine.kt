package com.github.kr328.clash.service.scene

import com.github.kr328.clash.service.model.Scene
import com.github.kr328.clash.service.model.SceneNetworkType
import com.github.kr328.clash.service.model.SceneTimeWindow
import java.util.Locale

data class SceneNetworkSnapshot(
    val connected: Boolean,
    val wifi: Boolean,
    val metered: Boolean,
    val ssid: String? = null,
)

data class SceneMoment(
    val isoDayOfWeek: Int,
    val minuteOfDay: Int,
)

data class SceneMatch(
    val scene: Scene,
    val reason: String,
)

object SceneEngine {
    fun resolve(
        scenes: List<Scene>,
        network: SceneNetworkSnapshot,
        moment: SceneMoment,
        ssidMatchingEnabled: Boolean,
    ): SceneMatch? {
        return scenes.withIndex()
            .filter { it.value.enabled }
            .sortedWith(compareBy<IndexedValue<Scene>> { it.value.priority }.thenBy { it.index })
            .firstNotNullOfOrNull { indexed ->
                val scene = indexed.value
                if (!matchesNetwork(scene, network)) return@firstNotNullOfOrNull null
                if (!matchesTimeWindow(scene.trigger.timeWindow, moment)) {
                    return@firstNotNullOfOrNull null
                }
                if (!matchesSsid(scene, network, ssidMatchingEnabled)) {
                    return@firstNotNullOfOrNull null
                }

                SceneMatch(
                    scene = scene,
                    reason = buildReason(scene, network, moment),
                )
            }
    }

    private fun matchesNetwork(scene: Scene, network: SceneNetworkSnapshot): Boolean {
        if (!network.connected) return false

        return when (scene.trigger.networkType) {
            SceneNetworkType.UnmeteredWifi -> network.wifi && !network.metered
            SceneNetworkType.Metered -> network.metered
            SceneNetworkType.Any -> true
        }
    }

    private fun matchesSsid(
        scene: Scene,
        network: SceneNetworkSnapshot,
        ssidMatchingEnabled: Boolean,
    ): Boolean {
        val required = normalizeSsid(scene.trigger.ssid) ?: return true
        if (!ssidMatchingEnabled) return false

        return normalizeSsid(network.ssid) == required
    }

    internal fun matchesTimeWindow(window: SceneTimeWindow?, moment: SceneMoment): Boolean {
        if (window == null) return true
        if (moment.isoDayOfWeek !in 1..7 || moment.minuteOfDay !in 0 until MINUTES_PER_DAY) {
            return false
        }
        if (window.startMinute !in 0 until MINUTES_PER_DAY ||
            window.endMinute !in 0 until MINUTES_PER_DAY ||
            window.days.isEmpty()
        ) {
            return false
        }

        if (window.startMinute == window.endMinute) {
            return moment.isoDayOfWeek in window.days
        }

        if (window.startMinute < window.endMinute) {
            return moment.isoDayOfWeek in window.days &&
                    moment.minuteOfDay >= window.startMinute &&
                    moment.minuteOfDay < window.endMinute
        }

        return if (moment.minuteOfDay >= window.startMinute) {
            moment.isoDayOfWeek in window.days
        } else if (moment.minuteOfDay < window.endMinute) {
            previousIsoDay(moment.isoDayOfWeek) in window.days
        } else {
            false
        }
    }

    private fun buildReason(
        scene: Scene,
        network: SceneNetworkSnapshot,
        moment: SceneMoment,
    ): String {
        val networkReason = when (scene.trigger.networkType) {
            SceneNetworkType.UnmeteredWifi -> "unmetered-wifi"
            SceneNetworkType.Metered -> "metered"
            SceneNetworkType.Any -> "any-network"
        }
        val ssidReason = normalizeSsid(scene.trigger.ssid)?.let { ",ssid=matched" }.orEmpty()
        return "$networkReason,day=${moment.isoDayOfWeek},minute=${moment.minuteOfDay}$ssidReason"
    }

    private fun normalizeSsid(value: String?): String? {
        return value
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotEmpty() && !it.equals("<unknown ssid>", ignoreCase = true) }
            ?.lowercase(Locale.ROOT)
    }

    private fun previousIsoDay(day: Int): Int = if (day == 1) 7 else day - 1

    private const val MINUTES_PER_DAY = 24 * 60
}
