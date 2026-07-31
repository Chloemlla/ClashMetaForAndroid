package com.github.kr328.clash.service.scene

import com.github.kr328.clash.service.model.Scene
import com.github.kr328.clash.service.model.SceneAction
import com.github.kr328.clash.service.model.SceneMode
import com.github.kr328.clash.service.model.SceneNetworkType
import com.github.kr328.clash.service.model.SceneTimeWindow
import com.github.kr328.clash.service.model.SceneTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SceneEngineTest {
    @Test
    fun resolve_usesLowestPriorityNumberAmongMatches() {
        val lowerPriority = scene(id = "later", priority = 10, mode = SceneMode.Rule)
        val higherPriority = scene(id = "first", priority = 1, mode = SceneMode.Direct)

        val match = SceneEngine.resolve(
            scenes = listOf(lowerPriority, higherPriority),
            network = unmeteredWifi(),
            moment = monday(12, 0),
            ssidMatchingEnabled = false,
        )

        assertEquals("first", match?.scene?.id)
        assertEquals(SceneMode.Direct, match?.scene?.action?.mode)
    }

    @Test
    fun resolve_ignoresDisabledScenes() {
        val disabled = scene(id = "disabled", priority = 0, enabled = false)

        assertNull(
            SceneEngine.resolve(
                scenes = listOf(disabled),
                network = unmeteredWifi(),
                moment = monday(12, 0),
                ssidMatchingEnabled = false,
            ),
        )
    }

    @Test
    fun resolve_matchesMeteredAndUnmeteredWifiSeparately() {
        val home = scene(
            id = "home",
            priority = 0,
            networkType = SceneNetworkType.UnmeteredWifi,
        )
        val away = scene(
            id = "away",
            priority = 1,
            networkType = SceneNetworkType.Metered,
        )

        assertEquals(
            "home",
            SceneEngine.resolve(
                listOf(home, away),
                unmeteredWifi(),
                monday(12, 0),
                false,
            )?.scene?.id,
        )
        assertEquals(
            "away",
            SceneEngine.resolve(
                listOf(home, away),
                SceneNetworkSnapshot(connected = true, wifi = false, metered = true),
                monday(12, 0),
                false,
            )?.scene?.id,
        )
    }

    @Test
    fun overnightWindow_usesStartingDayBeforeAndAfterMidnight() {
        val window = SceneTimeWindow(
            startMinute = 22 * 60,
            endMinute = 6 * 60,
            days = setOf(5),
        )

        assertEquals(true, SceneEngine.matchesTimeWindow(window, SceneMoment(5, 23 * 60)))
        assertEquals(true, SceneEngine.matchesTimeWindow(window, SceneMoment(6, 2 * 60)))
        assertEquals(false, SceneEngine.matchesTimeWindow(window, SceneMoment(7, 2 * 60)))
        assertEquals(false, SceneEngine.matchesTimeWindow(window, SceneMoment(5, 12 * 60)))
    }

    @Test
    fun ssidRequirement_failsClosedWhenEnhancementDisabledOrUnavailable() {
        val scene = scene(id = "ssid", priority = 0, ssid = "Home WiFi")

        assertNull(
            SceneEngine.resolve(
                listOf(scene),
                unmeteredWifi(ssid = "Home WiFi"),
                monday(12, 0),
                ssidMatchingEnabled = false,
            ),
        )
        assertNull(
            SceneEngine.resolve(
                listOf(scene),
                unmeteredWifi(ssid = null),
                monday(12, 0),
                ssidMatchingEnabled = true,
            ),
        )
        assertEquals(
            "ssid",
            SceneEngine.resolve(
                listOf(scene),
                unmeteredWifi(ssid = "\"HOME WIFI\""),
                monday(12, 0),
                ssidMatchingEnabled = true,
            )?.scene?.id,
        )
    }

    private fun scene(
        id: String,
        priority: Int,
        enabled: Boolean = true,
        mode: SceneMode = SceneMode.Rule,
        networkType: SceneNetworkType = SceneNetworkType.Any,
        ssid: String? = null,
    ): Scene {
        return Scene(
            id = id,
            name = id,
            enabled = enabled,
            priority = priority,
            trigger = SceneTrigger(networkType = networkType, ssid = ssid),
            action = SceneAction(mode = mode),
        )
    }

    private fun unmeteredWifi(ssid: String? = null): SceneNetworkSnapshot {
        return SceneNetworkSnapshot(
            connected = true,
            wifi = true,
            metered = false,
            ssid = ssid,
        )
    }

    private fun monday(hour: Int, minute: Int): SceneMoment {
        return SceneMoment(isoDayOfWeek = 1, minuteOfDay = hour * 60 + minute)
    }
}
