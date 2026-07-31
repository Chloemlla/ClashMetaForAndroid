package com.github.kr328.clash.service.scene

const val MIN_FAILOVER_THRESHOLD = 2
const val MAX_FAILOVER_THRESHOLD = 5
const val MIN_FAILOVER_COOLDOWN_MILLIS = 30_000L
const val MAX_FAILOVER_COOLDOWN_MILLIS = 300_000L

fun normalizeFailoverThreshold(value: Int): Int {
    return value.coerceIn(MIN_FAILOVER_THRESHOLD, MAX_FAILOVER_THRESHOLD)
}

fun normalizeFailoverCooldownMillis(value: Long): Long {
    return value.coerceIn(MIN_FAILOVER_COOLDOWN_MILLIS, MAX_FAILOVER_COOLDOWN_MILLIS)
}

data class FailoverNode(
    val group: String,
    val node: String,
)

data class NodeFailoverState(
    val failureStreaks: Map<FailoverNode, Int> = emptyMap(),
    val lastSwitchAt: Map<String, Long> = emptyMap(),
)

sealed class NodeFailoverDecision {
    data class NoSwitch(
        val failureCount: Int,
        val cooldownRemainingMillis: Long = 0L,
    ) : NodeFailoverDecision()

    data class Switch(
        val from: String,
        val to: String,
        val failureCount: Int,
    ) : NodeFailoverDecision()
}

data class NodeFailoverTransition(
    val state: NodeFailoverState,
    val decision: NodeFailoverDecision,
)

object NodeFailoverStateMachine {
    fun transition(
        state: NodeFailoverState,
        group: String,
        selectedNode: String,
        selectedNodeHealthy: Boolean,
        orderedCandidates: List<String>,
        healthyCandidates: Set<String>,
        threshold: Int,
        cooldownMillis: Long,
        nowMillis: Long,
    ): NodeFailoverTransition {
        val node = FailoverNode(group, selectedNode)
        if (selectedNodeHealthy) {
            val streaks = state.failureStreaks - node
            return NodeFailoverTransition(
                state = state.copy(failureStreaks = streaks),
                decision = NodeFailoverDecision.NoSwitch(failureCount = 0),
            )
        }

        val failureCount = (state.failureStreaks[node] ?: 0) + 1
        val failedState = state.copy(
            failureStreaks = state.failureStreaks + (node to failureCount),
        )
        if (failureCount < normalizeFailoverThreshold(threshold)) {
            return NodeFailoverTransition(
                state = failedState,
                decision = NodeFailoverDecision.NoSwitch(failureCount),
            )
        }

        val lastSwitch = state.lastSwitchAt[group]
        val cooldown = normalizeFailoverCooldownMillis(cooldownMillis)
        if (lastSwitch != null) {
            val elapsed = nowMillis - lastSwitch
            if (elapsed < 0L || elapsed < cooldown) {
                val remaining = if (elapsed < 0L) cooldown else cooldown - elapsed
                return NodeFailoverTransition(
                    state = failedState,
                    decision = NodeFailoverDecision.NoSwitch(
                        failureCount = failureCount,
                        cooldownRemainingMillis = remaining,
                    ),
                )
            }
        }

        val target = nextHealthyCandidate(
            selectedNode = selectedNode,
            orderedCandidates = orderedCandidates,
            healthyCandidates = healthyCandidates,
        ) ?: return NodeFailoverTransition(
            state = failedState,
            decision = NodeFailoverDecision.NoSwitch(failureCount),
        )

        return NodeFailoverTransition(
            state = failedState.copy(
                failureStreaks = failedState.failureStreaks - node,
                lastSwitchAt = failedState.lastSwitchAt + (group to nowMillis),
            ),
            decision = NodeFailoverDecision.Switch(
                from = selectedNode,
                to = target,
                failureCount = failureCount,
            ),
        )
    }

    private fun nextHealthyCandidate(
        selectedNode: String,
        orderedCandidates: List<String>,
        healthyCandidates: Set<String>,
    ): String? {
        val candidates = orderedCandidates.filter { it.isNotBlank() }.distinct()
        if (candidates.size < 2) return null

        val selectedIndex = candidates.indexOf(selectedNode)
        if (selectedIndex < 0) {
            return candidates.firstOrNull { it != selectedNode && it in healthyCandidates }
        }

        for (offset in 1 until candidates.size) {
            val candidate = candidates[(selectedIndex + offset) % candidates.size]
            if (candidate in healthyCandidates) return candidate
        }

        return null
    }
}
