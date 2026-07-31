# Profile-Scoped Preference Contract

## 1. Scope / Trigger

Use this contract whenever a setting can differ between imported subscriptions/profiles. A profile-owned setting must never be represented only by a process-wide or application-wide preference.

The traffic billing implementation is the reference case: each profile chooses local-from-zero or upstream `subscription-userinfo` billing independently.

## 2. Signatures

```kotlin
fun getLocalSubscriptionTraffic(uuid: UUID): Boolean
internal fun getLocalSubscriptionTrafficIfPresent(uuid: UUID): Boolean?
fun setLocalSubscriptionTraffic(uuid: UUID, enabled: Boolean)
fun clearLocalSubscriptionTraffic(uuid: UUID)
```

Persistence keys:

```text
Legacy/default: local_subscription_traffic
Scoped:         local_subscription_traffic_profile_<uuid>
```

## 3. Contracts

- The stable owner is the existing profile `UUID`; names, URLs, list positions, and active/inactive state are not persistence keys.
- A normal profile read lazily copies the legacy/default Boolean into a missing scoped key exactly once.
- The global app setting remains only the default for profiles without a scoped value.
- Runtime finalization may use the non-migrating `IfPresent` read so deleted profiles cannot recreate scoped state.
- Clone copies the source profile's effective billing mode to the new UUID.
- Cancel clears the scoped key only for pending-only profiles; editing an existing imported profile must retain its value.
- Delete clears local counters and the scoped preference. If the deleted UUID is active, clear `activeProfile` before cleanup.

## 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Scoped key exists and is Boolean | Return it unchanged |
| Scoped key is missing during normal read | Persist and return the legacy/default value |
| Scoped key is missing during final runtime flush | Return `null`; do not write or accumulate |
| Scoped key has an invalid type | Replace it with the legacy/default Boolean |
| One profile changes mode | No other scoped key changes |
| Pending-only profile is canceled | Remove its scoped key |
| Existing profile edit is canceled | Keep its scoped key |
| Active profile is deleted | Clear active UUID, counters, and scoped key without recreation |

## 5. Good / Base / Bad Cases

- Good: Profile A stores `true`, Profile B stores `false`; switching profiles preserves both values.
- Base: A legacy install opens Profile A for the first time; its scoped key is seeded from the old global preference.
- Bad: UI or runtime code reads `localSubscriptionTraffic` directly to decide one profile's behavior.
- Bad: A delete-time accounting flush calls the migrating getter after the scoped key was removed.

## 6. Tests Required

- Legacy default migrates once per UUID and later default changes do not overwrite it.
- Two UUIDs retain opposite values independently.
- Non-migrating lookup of a missing UUID returns `null` and performs no write.
- Clear removes only the target UUID and permits a later explicit migration.
- Invalid stored types are repaired deterministically.
- Lifecycle review asserts clone-copy, pending-cancel, existing-edit cancel, and active-delete call sites.

Actual Android/Gradle checks run only in GitHub Actions per repository policy.

## 7. Wrong vs Correct

### Wrong

```kotlin
val useLocal = serviceStore.localSubscriptionTraffic
serviceStore.localSubscriptionTraffic = selected
```

This makes the last edited profile control every subscription.

### Correct

```kotlin
val useLocal = serviceStore.getLocalSubscriptionTraffic(profile.uuid)
serviceStore.setLocalSubscriptionTraffic(profile.uuid, selected)
```

Runtime cleanup uses the non-migrating lookup when a deleted/stale UUID must not be recreated.
