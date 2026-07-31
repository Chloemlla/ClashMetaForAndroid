package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.service.ProfileProcessor
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Scene
import com.github.kr328.clash.service.model.SceneAction
import com.github.kr328.clash.service.model.SceneMode
import com.github.kr328.clash.service.scene.AutomationNotifier
import com.github.kr328.clash.service.scene.SceneEngine
import com.github.kr328.clash.service.scene.SceneMoment
import com.github.kr328.clash.service.scene.SceneNetworkSnapshot
import com.github.kr328.clash.service.scene.SceneStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

class SceneModule(
    service: Service,
    private val networkSnapshot: (includeSsid: Boolean) -> SceneNetworkSnapshot,
) : Module<Unit>(service) {
    private val store = ServiceStore(service)
    private val sceneStore = SceneStore(service)
    private val requested = Channel<Unit>(Channel.CONFLATED)
    private var lastApplied: AppliedScene? = null

    fun requestEvaluation() {
        requested.trySend(Unit)
    }

    override suspend fun run() = coroutineScope {
        val settingsChanged = receiveBroadcast {
            addAction(Intents.ACTION_AUTOMATION_CHANGED)
        }
        val clockChanged = receiveBroadcast(requireSelf = false, capacity = Channel.CONFLATED) {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        val ticker = ticker(TimeUnit.MINUTES.toMillis(1))

        requestEvaluation()

        while (true) {
            select<Unit> {
                requested.onReceive { evaluateSafely() }
                settingsChanged.onReceive { evaluateSafely() }
                clockChanged.onReceive { evaluateSafely() }
                ticker.onReceive { evaluateSafely() }
            }
        }
    }

    private suspend fun evaluateSafely() {
        try {
            evaluate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Scene evaluation failed", e)
        }
    }

    private suspend fun evaluate() {
        if (!store.autoScenesEnabled) {
            lastApplied = null
            return
        }

        val calendar = Calendar.getInstance()
        val moment = SceneMoment(
            isoDayOfWeek = calendar.toIsoDayOfWeek(),
            minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE),
        )
        val ssidMatchingEnabled = store.sceneSsidMatchingEnabled
        val match = SceneEngine.resolve(
            scenes = sceneStore.scenes,
            network = networkSnapshot(ssidMatchingEnabled),
            moment = moment,
            ssidMatchingEnabled = ssidMatchingEnabled,
        )
        if (match == null) {
            lastApplied = null
            return
        }

        val applied = AppliedScene(match.scene.id, match.scene.action)
        if (lastApplied == applied) return

        apply(match.scene)
        lastApplied = applied

        Log.i("Scene applied name=${match.scene.name} reason=${match.reason}")
        AutomationNotifier.notifyScene(service, match.scene)
    }

    private suspend fun apply(scene: Scene) {
        val profileText = scene.action.profileId?.trim()?.takeIf { it.isNotEmpty() }
        val profileId = profileText?.toUuidOrNull()

        val expectedMode = scene.action.mode.toTunnelMode()
        var modeChanged = false
        if (Clash.queryTunnelState().mode != expectedMode) {
            val override = Clash.queryOverride(Clash.OverrideSlot.Session)
            override.mode = expectedMode
            Clash.patchOverride(Clash.OverrideSlot.Session, override)
            modeChanged = true
        }

        var switchProfile = false
        var profileUnavailable = false
        if (profileText != null && profileId == null) {
            profileUnavailable = true
            Log.w("Scene profile id is invalid scene=${scene.name}")
        } else if (profileId != null && profileId != store.activeProfile) {
            val exists = ImportedDao().exists(profileId)
            switchProfile = exists
            profileUnavailable = !exists
        }

        if (switchProfile) {
            ProfileProcessor.active(service, checkNotNull(profileId))
            if (store.activeProfile != profileId) {
                if (modeChanged) {
                    service.sendOverrideChanged()
                }
                throw IllegalStateException(
                    "Scene profile activation did not complete scene=${scene.name} profile=$profileId",
                )
            }
        } else if (modeChanged) {
            service.sendOverrideChanged()
        }

        if (profileUnavailable && profileId != null) {
            Log.w("Scene profile is unavailable scene=${scene.name} profile=$profileId")
        }
    }

    private fun SceneMode.toTunnelMode(): TunnelState.Mode = when (this) {
        SceneMode.Direct -> TunnelState.Mode.Direct
        SceneMode.Global -> TunnelState.Mode.Global
        SceneMode.Rule -> TunnelState.Mode.Rule
    }

    private fun String.toUuidOrNull(): UUID? {
        return takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    private fun Calendar.toIsoDayOfWeek(): Int = when (get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        else -> 7
    }

    private data class AppliedScene(
        val id: String,
        val action: SceneAction,
    )
}
