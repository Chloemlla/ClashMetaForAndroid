# Scene Automation and Failover Contract

## 1. Scope / Trigger

Use this contract when changing scene persistence/evaluation, network or clock hooks, Mode/Profile actions, health-check failover, or automation notifications.

Reference paths: `service/src/main/java/com/github/kr328/clash/service/model/Scene.kt`, `service/src/main/java/com/github/kr328/clash/service/clash/module/SceneModule.kt`, and `service/src/main/java/com/github/kr328/clash/service/scene/`.

Both automation features are opt-in: `ServiceStore.autoScenesEnabled` and `autoFailoverEnabled` default to `false`, and built-in scene templates are disabled. A scene may patch Mode and optionally activate a profile, but it must never start, stop, or toggle the VPN. `SceneModule` runs only inside an already-running Clash/TUN service.

## 2. Signatures

```kotlin
fun SceneEngine.resolve(
    scenes: List<Scene>,
    network: SceneNetworkSnapshot,
    moment: SceneMoment,
    ssidMatchingEnabled: Boolean,
): SceneMatch?

fun NodeFailoverStateMachine.transition(
    state: NodeFailoverState,
    group: String,
    selectedNode: String,
    selectedNodeHealthy: Boolean,
    orderedCandidates: List<String>,
    healthyCandidates: Set<String>,
    threshold: Int,
    cooldownMillis: Long,
    nowMillis: Long,
): NodeFailoverTransition

fun normalizeFailoverThreshold(value: Int): Int       // 2..5
fun normalizeFailoverCooldownMillis(value: Long): Long // 30_000..300_000
suspend fun NodeFailoverController.onHealthCheckCompleted(
    groupName: String,
    completedSuccessfully: Boolean,
)
```

Evaluation inputs use ISO day `1..7`, minute `0..1439`, and a network snapshot containing `connected`, `wifi`, `metered`, and optional `ssid`.

## 3. Contracts

- Resolve only enabled scenes. Lowest numeric `priority` wins; equal priorities retain input order. `SceneStore` removes blank/duplicate IDs and rewrites priorities to list order.
- Every scene requires a connected network. `UnmeteredWifi` means Wi-Fi and not metered; `Metered` means metered; `Any` still does not match offline state.
- A null time window matches any valid moment. A normal window is `[start, end)` on selected days; an overnight window attributes its after-midnight portion to the previous/start day. Equal start/end means the whole selected day. Invalid days, minutes, or empty day sets fail closed.
- SSID is an optional enhancement. A nonblank scene SSID matches only when the separate default-off SSID toggle is enabled and an available SSID matches after trim, quote removal, and case folding. Missing/unknown SSID fails closed; a scene without an SSID remains network/time-only.
- Evaluate on network events, automation-setting changes, clock/date/timezone changes, and the one-minute ticker. Conflate requests; do not add a busy retry loop.
- Apply Mode/Profile only. If a nonblank `profileId` is configured, it must parse as a UUID, identify an imported profile, be activated, and then equal `ServiceStore.activeProfile`. Invalid, missing, or unverified activation is failure, not a partial success.
- Set `lastApplied`, write the success log, and post the scene notification only after the complete action succeeds. On failure, preserve cancellation, leave success uncached, log the error, and retry on the next normal evaluation trigger. Re-entering a match after no match/disabled state may notify again; an unchanged successful scene/action is deduplicated.
- Scene notifications default on but obey `sceneNotificationsEnabled` and notification permission. Notification failure must not change the action result.
- Failover is evaluated after each group health check, including exceptional completion, and only for selector groups. Count consecutive failures by `(activeProfile, group, selectedNode)`; a healthy result clears that node's streak.
- Runtime failover state is profile-scoped: changing/clearing the active profile or disabling failover resets streaks and cooldowns. Re-check the active UUID before selector patch persistence so a race cannot write `Selection` for another profile.
- Normalize threshold and cooldown at both settings and state-machine boundaries. Corrupt values below/above policy become `2..5` failures and `30..300` seconds; raw persisted values never weaken the minimum safeguards.
- At threshold, choose the next healthy non-group candidate in the current configured sort order, with wraparound. Cooldown is per group. No candidate, active cooldown, clock rollback, rejected selector patch, or profile race produces no switch.
- Reset the failed-node streak and record cooldown only after `Clash.patchSelector` succeeds. Persist `Selection(activeProfile, group, target)`, then log and optionally notify. A rejected patch retains the failure count so the next health check can retry; notify only after a successful switch. Failover notifications default on and obey their toggle.

## 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Both master toggles untouched | No scene action and no automatic node switch |
| Service is stopped | Automation does not start it or the VPN |
| Multiple scenes match | Choose lowest priority number, then stable list order |
| Offline snapshot | No scene matches, including `Any` |
| Invalid time window | Fail closed without throwing |
| Scene requires SSID; toggle off or SSID unavailable | Scene does not match; network-only scenes still work |
| Target profile is invalid, missing, or not active after activation | Do not cache/log/notify success; retry on a later evaluation |
| Scene application throws `CancellationException` | Rethrow; do not convert it to a retryable success |
| Corrupt threshold `1` / cooldown `0` | Require 2 failures / 30-second cooldown |
| Healthy selected node | Clear only that profile/group/node streak |
| Active profile changes during failover | Reset state and do not persist the old profile's selection |
| Selector patch is rejected | No notification or cooldown commit; retain failure streak for retry |
| Notification disabled or permission denied | Action/switch remains successful without a notification |

## 5. Good / Base / Bad Cases

- Good: two scenes match; priority `0` wins, its profile activation is verified, then one notification is posted.
- Base: Auto scenes and failover remain off; manual Mode, profile, node, and VPN behavior is unchanged.
- Good: an overnight Friday `22:00-06:00` scene matches Saturday `02:00`; an SSID scene fails closed when SSID access is unavailable.
- Good: Profile A accumulates failures, then switching to Profile B clears the state before B's health check.
- Bad: treating a missing scene profile as “Mode-only success” and suppressing later retries.
- Bad: using raw persisted threshold/cooldown values or sharing one failover state across profiles.
- Bad: notifying before profile verification or before `patchSelector` succeeds.
- Bad: adding START/STOP/TOGGLE as a scene action.

## 6. Tests Required

- Scene priority, disabled filtering, offline behavior, metered versus unmetered Wi-Fi, equal-priority stability, and corrupt-store disabled-template fallback.
- Same-day, full-day, overnight, invalid-day/minute, and empty-day time windows.
- SSID toggle off, unavailable/unknown SSID, quote/case normalization, and network-only fallback.
- Scene-module action tests proving verified profile activation gates `lastApplied`, logging, notification, and later retry.
- Failover threshold, success reset, corrupt low/high normalization, cooldown/clock rollback, ordered wraparound, no healthy alternative, and rejected patch retry.
- Controller tests proving active-profile changes isolate state and selection persistence/notifications occur only after a successful patch.

Actual Android/Gradle checks run only in GitHub Actions per repository policy.

## 7. Wrong vs Correct

### Wrong

```kotlin
applyMode(scene.action.mode)
profileId?.let { ProfileProcessor.active(context, it) }
lastApplied = scene.id
AutomationNotifier.notifyScene(context, scene)
```

This reports success even when the profile is absent or activation did not complete.

### Correct

```kotlin
applyMode(scene.action.mode)
profileId?.let {
    require(ImportedDao().exists(it))
    ProfileProcessor.active(context, it)
    check(ServiceStore(context).activeProfile == it)
}
lastApplied = AppliedScene(scene.id, scene.action)
AutomationNotifier.notifyScene(context, scene)
```

If validation or activation fails, do not update the success cache; the next network/settings/clock/ticker event retries.
