package com.github.kr328.clash.service.scene

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class NodeFailoverController(private val context: Context) {
    private val store = ServiceStore(context)
    private val lock = Mutex()
    private var state = NodeFailoverState()
    private var trackedProfile: UUID? = null

    suspend fun onHealthCheckCompleted(groupName: String, completedSuccessfully: Boolean) {
        lock.withLock {
            if (!store.autoFailoverEnabled) {
                state = NodeFailoverState()
                trackedProfile = null
                return
            }

            val activeProfile = store.activeProfile ?: run {
                state = NodeFailoverState()
                trackedProfile = null
                return
            }
            if (trackedProfile != activeProfile) {
                state = NodeFailoverState()
                trackedProfile = activeProfile
            }

            val group = Clash.queryGroup(groupName, store.failoverSort)
            if (!group.type.equals("Selector", ignoreCase = true)) return

            val selected = group.now.takeIf { it.isNotBlank() } ?: return
            val selectedProxy = group.proxies.firstOrNull { it.name == selected }
            val selectedHealthy = completedSuccessfully && (selectedProxy?.delay ?: 0) > 0
            val candidates = group.proxies.filterNot { it.isGroup }
            val transition = NodeFailoverStateMachine.transition(
                state = state,
                group = groupName,
                selectedNode = selected,
                selectedNodeHealthy = selectedHealthy,
                orderedCandidates = candidates.map { it.name },
                healthyCandidates = candidates.filter { it.delay > 0 }.mapTo(mutableSetOf()) { it.name },
                threshold = store.failoverThreshold,
                cooldownMillis = store.failoverCooldownMillis,
                nowMillis = System.currentTimeMillis(),
            )

            val decision = transition.decision as? NodeFailoverDecision.Switch
            if (decision == null) {
                state = transition.state
                return
            }
            if (store.activeProfile != activeProfile) {
                state = NodeFailoverState()
                trackedProfile = store.activeProfile
                return
            }
            if (!Clash.patchSelector(groupName, decision.to)) {
                state = state.copy(
                    failureStreaks = state.failureStreaks +
                            (FailoverNode(groupName, selected) to decision.failureCount),
                )
                Log.w(
                    "Node failover rejected group=$groupName from=${decision.from} to=${decision.to}",
                )
                return
            }

            state = transition.state
            if (store.activeProfile == activeProfile) {
                runCatching {
                    SelectionDao().setSelected(Selection(activeProfile, groupName, decision.to))
                }.onFailure {
                    Log.w("Node failover selection persistence failed group=$groupName", it)
                }
            } else {
                Log.w("Node failover profile changed before selection persistence group=$groupName")
            }
            Log.i(
                "Node failover switched group=$groupName from=${decision.from} " +
                        "to=${decision.to} failures=${decision.failureCount}",
            )
            AutomationNotifier.notifyFailover(
                context = context,
                group = groupName,
                from = decision.from,
                to = decision.to,
            )
        }
    }

}
