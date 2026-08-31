package com.github.kr328.clash.service.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeFailoverStateMachineTest {
    @Test
    fun switchesOnConfiguredFailureThreshold() {
        var state = NodeFailoverState()

        repeat(2) {
            val transition = failure(state, nowMillis = it.toLong())
            state = transition.state
            assertTrue(transition.decision is NodeFailoverDecision.NoSwitch)
        }

        val third = failure(state, nowMillis = 2L)
        val decision = third.decision as NodeFailoverDecision.Switch
        assertEquals("node-a", decision.from)
        assertEquals("node-b", decision.to)
        assertEquals(3, decision.failureCount)
    }

    @Test
    fun successResetsConsecutiveFailureCount() {
        var state = failure(NodeFailoverState(), nowMillis = 0L).state
        state = transition(state, healthy = true, nowMillis = 1L).state

        val next = failure(state, nowMillis = 2L)
        val decision = next.decision as NodeFailoverDecision.NoSwitch
        assertEquals(1, decision.failureCount)
    }

    @Test
    fun cooldownPreventsThrashingBetweenSwitches() {
        val key = FailoverNode("group", "node-a")
        val state = NodeFailoverState(
            failureStreaks = mapOf(key to 2),
            lastSwitchAt = mapOf("group" to 1_000L),
        )

        val blocked = failure(state, nowMillis = 30_000L)
        val decision = blocked.decision as NodeFailoverDecision.NoSwitch
        assertEquals(3, decision.failureCount)
        assertEquals(31_000L, decision.cooldownRemainingMillis)

        val allowed = failure(blocked.state, nowMillis = 61_000L)
        assertTrue(allowed.decision is NodeFailoverDecision.Switch)
    }

    @Test
    fun choosesNextHealthyCandidateInCurrentOrderWithWraparound() {
        val state = NodeFailoverState(
            failureStreaks = mapOf(FailoverNode("group", "node-c") to 2),
        )
        val transition = NodeFailoverStateMachine.transition(
            state = state,
            group = "group",
            selectedNode = "node-c",
            selectedNodeHealthy = false,
            orderedCandidates = listOf("node-a", "node-b", "node-c"),
            healthyCandidates = setOf("node-b"),
            threshold = 3,
            cooldownMillis = 60_000L,
            nowMillis = 5_000L,
        )

        assertEquals("node-b", (transition.decision as NodeFailoverDecision.Switch).to)
    }

    @Test
    fun keepsFailureStreakWhenNoHealthyAlternativeExists() {
        val state = NodeFailoverState(
            failureStreaks = mapOf(FailoverNode("group", "node-a") to 2),
        )

        val transition = transition(
            state = state,
            healthy = false,
            nowMillis = 3L,
            healthyCandidates = emptySet(),
        )

        assertTrue(transition.decision is NodeFailoverDecision.NoSwitch)
        assertEquals(3, transition.state.failureStreaks[FailoverNode("group", "node-a")])
    }

    @Test
    fun policyRequiresRepeatedFailuresEvenForCorruptLowThreshold() {
        val first = NodeFailoverStateMachine.transition(
            state = NodeFailoverState(),
            group = "group",
            selectedNode = "node-a",
            selectedNodeHealthy = false,
            orderedCandidates = listOf("node-a", "node-b"),
            healthyCandidates = setOf("node-b"),
            threshold = 1,
            cooldownMillis = 60_000L,
            nowMillis = 0L,
        )

        assertTrue(first.decision is NodeFailoverDecision.NoSwitch)
        val second = NodeFailoverStateMachine.transition(
            state = first.state,
            group = "group",
            selectedNode = "node-a",
            selectedNodeHealthy = false,
            orderedCandidates = listOf("node-a", "node-b"),
            healthyCandidates = setOf("node-b"),
            threshold = 1,
            cooldownMillis = 60_000L,
            nowMillis = 1L,
        )
        assertTrue(second.decision is NodeFailoverDecision.Switch)
    }

    @Test
    fun policyKeepsMinimumCooldownForCorruptZeroValue() {
        val state = NodeFailoverState(
            failureStreaks = mapOf(FailoverNode("group", "node-a") to 1),
            lastSwitchAt = mapOf("group" to 0L),
        )

        val transition = NodeFailoverStateMachine.transition(
            state = state,
            group = "group",
            selectedNode = "node-a",
            selectedNodeHealthy = false,
            orderedCandidates = listOf("node-a", "node-b"),
            healthyCandidates = setOf("node-b"),
            threshold = 1,
            cooldownMillis = 0L,
            nowMillis = 1_000L,
        )

        val decision = transition.decision as NodeFailoverDecision.NoSwitch
        assertEquals(29_000L, decision.cooldownRemainingMillis)
    }

    @Test
    fun dropsFailureStreaksOfNodesMissingFromCurrentGroup() {
        val state = NodeFailoverState(
            failureStreaks = mapOf(
                FailoverNode("group", "node-gone") to 2,
                FailoverNode("other", "node-gone") to 2,
                FailoverNode("group", "node-b") to 1,
            ),
        )

        val transition = failure(state, nowMillis = 0L)

        assertNull(transition.state.failureStreaks[FailoverNode("group", "node-gone")])
        assertEquals(2, transition.state.failureStreaks[FailoverNode("other", "node-gone")])
        assertEquals(1, transition.state.failureStreaks[FailoverNode("group", "node-b")])
    }

    @Test
    fun forwardClockJumpKeepsCooldownAndReanchors() {
        val state = NodeFailoverState(
            failureStreaks = mapOf(FailoverNode("group", "node-a") to 2),
            lastSwitchAt = mapOf("group" to 1_000L),
        )

        val jumped = failure(state, nowMillis = 1_000L + 60_000L * 10)
        val decision = jumped.decision as NodeFailoverDecision.NoSwitch
        assertEquals(60_000L, decision.cooldownRemainingMillis)
        assertEquals(1_000L + 60_000L * 10, jumped.state.lastSwitchAt.getValue("group"))

        val allowed = failure(jumped.state, nowMillis = 1_000L + 60_000L * 11)
        assertTrue(allowed.decision is NodeFailoverDecision.Switch)
    }

    @Test
    fun backwardClockJumpDoesNotBlockFailoverForever() {
        val state = NodeFailoverState(
            failureStreaks = mapOf(FailoverNode("group", "node-a") to 2),
            lastSwitchAt = mapOf("group" to 500_000L),
        )

        val rolledBack = failure(state, nowMillis = 1_000L)
        assertTrue(rolledBack.decision is NodeFailoverDecision.NoSwitch)
        assertEquals(1_000L, rolledBack.state.lastSwitchAt.getValue("group"))

        val allowed = failure(rolledBack.state, nowMillis = 61_000L)
        assertTrue(allowed.decision is NodeFailoverDecision.Switch)
    }

    private fun failure(state: NodeFailoverState, nowMillis: Long): NodeFailoverTransition {
        return transition(state, healthy = false, nowMillis = nowMillis)
    }

    private fun transition(
        state: NodeFailoverState,
        healthy: Boolean,
        nowMillis: Long,
        healthyCandidates: Set<String> = setOf("node-b", "node-c"),
    ): NodeFailoverTransition {
        return NodeFailoverStateMachine.transition(
            state = state,
            group = "group",
            selectedNode = "node-a",
            selectedNodeHealthy = healthy,
            orderedCandidates = listOf("node-a", "node-b", "node-c"),
            healthyCandidates = healthyCandidates,
            threshold = 3,
            cooldownMillis = 60_000L,
            nowMillis = nowMillis,
        )
    }
}
