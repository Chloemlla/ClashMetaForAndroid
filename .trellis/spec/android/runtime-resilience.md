# Android Runtime Resilience — Contracts

## 1. Scope / Trigger

Use this contract when changing Binder/IPC calls, cross-thread shared state, native query cadence, or sensitive data persistence in the service/app layers. Covers the audit-driven fixes from `08-05-audit-fix-tracking`.

Reference paths:
- `app/src/main/java/com/github/kr328/clash/util/Remote.kt` — app-side `withClash`/`withProfile`
- `sdk/src/main/java/com/github/kr328/clash/sdk/ClashRuntime.kt` — SDK-side Binder wrappers
- `service/src/main/java/com/github/kr328/clash/service/StatusProvider.kt` — cross-process status flags
- `service/src/main/java/com/github/kr328/clash/service/clash/module/TrafficHistoryModule.kt` — sampling cadence
- `service/src/main/java/com/github/kr328/clash/service/clash/module/LocalTrafficAccountingModule.kt` — delta accounting
- `service/src/main/java/com/github/kr328/clash/service/util/SecureStorage.kt` — Keystore-backed encryption
- `service/src/main/java/com/github/kr328/clash/service/data/Converters.kt` — Room type converters
- `service/src/main/java/com/github/kr328/clash/service/data/migrations/LegacyMigration.kt` — legacy DB migration

## 2. Signatures

### Binder retry (bounded, with backoff)

```kotlin
// app-side (Remote.kt) and sdk-side (ClashRuntime.kt)
private const val MAX_RETRIES = 5
private const val RETRY_BASE_DELAY_MS = 100L

suspend fun <T> withClash(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IClashManager.() -> T,
): T {
    var attempt = 0
    while (true) {
        val remote = Remote.service.remote.get()
        val client = remote.clash()
        try {
            return withContext(context) { client.block() }
        } catch (e: DeadObjectException) {
            attempt += 1
            if (attempt > MAX_RETRIES) throw e
            Remote.service.remote.reset(remote)
            delay(RETRY_BASE_DELAY_MS * attempt)  // linear backoff
        }
    }
}
```

### Volatile status flags

```kotlin
@Volatile
var serviceRunning: Boolean = false
@Volatile
var vpnRunning: Boolean = false
@Volatile
var lastError: String? = null
@Volatile
var currentProfile: String? = null
```

### Secure storage

```kotlin
object SecureStorage {
    fun init(context: Context)   // call once in Application.onCreate
    fun encrypt(plaintext: String): String  // returns "iv||ciphertext" base64
    fun decrypt(encoded: String): String
}
```

### Room converter (encrypt-at-rest)

```kotlin
@TypeConverter
fun fromSecureString(value: String?): String? = value?.let {
    try { SecureStorage.encrypt(it) } catch (_: Exception) { it }  // fail-open fallback
}

@TypeConverter
fun toSecureString(value: String?): String? = value?.let {
    try { SecureStorage.decrypt(it) } catch (_: Exception) { it }  // legacy plaintext passthrough
}
```

## 3. Contracts

- **Binder retries**: never `while(true)` on `DeadObjectException`. Cap at 5 attempts with `delay(100ms * attempt)` linear backoff, then rethrow. Reset the remote reference before retrying.
- **Cross-thread flags**: static `var` read/written from multiple threads must be `@Volatile`. Compound read-modify-write (e.g., `serviceRunning` also touching `shouldStartClashOnBoot`) should use `synchronized` or a lock if consistency matters.
- **Native query cadence**: `Clash.queryMemoryUsage()` calls Go `runtime.ReadMemStats` which stop-the-worlds the proxy core. Sample at most every 30s; reuse the last known value between samples.
- **Delta accounting**: never reset a traffic baseline to 0 when a feature is disabled. Mark the baseline stale and re-anchor to current totals on re-enable, or deltas over-count.
- **Encrypted fields**: never query/index by an encrypted column (random IV breaks equality). Only encrypt fields that are read whole, like `ageSecretKey`.
- **Migration**: never `deleteDatabase()` on the legacy DB in a `finally` or outside the success path. Delete only after the migration try-block succeeds, so a failure can retry next launch.
- **Fail-open vs fail-closed**: Keystore encryption fallback is fail-open (store plaintext) so an unavailable Keystore cannot brick the DB; decryption is fail-open (return as-is) so legacy plaintext rows keep working.

## 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Binder dead once (transient) | Reset remote, retry with backoff |
| Binder dead 5+ times (permanent) | Rethrow `DeadObjectException` |
| Status flag read across threads | `@Volatile` guarantees visibility |
| Memory sample requested at 2s cadence | Return last known value; query native at most every 30s |
| Local accounting disabled | Skip `queryTrafficTotal()`; mark baseline stale |
| Local accounting re-enabled | Re-anchor baseline to current totals before first delta |
| Keystore unavailable on encrypt | Store plaintext (fail-open) |
| Keystore unavailable on decrypt | Return as-is (legacy plaintext passthrough) |
| Legacy migration throws | Keep legacy DB; log; retry next launch |

## 5. Good / Base / Bad Cases

- **Good**: Service restarts mid-Binder-call → 1-2 retries with backoff succeed.
- **Good**: Local accounting disabled → zero native traffic queries on the 2s tick.
- **Base**: No Binder death, no concurrency contention → behavior identical to before.
- **Base**: Memory widget shows 30s-stale value → acceptable, avoids proxy STW stalls.
- **Bad**: `while(true)` Binder retry → CPU spin / hang when service is dead.
- **Bad**: Resetting accounting baseline to 0 on disable → over-counts on re-enable.
- **Bad**: `deleteDatabase` on migration failure → permanent data loss, no retry.

## 6. Tests Required

- Binder retry: 0, 1, 5, 6 dead-call cases; verify bounded attempts + rethrow.
- StatusProvider: concurrent read/write visibility (volatile semantics).
- TrafficHistoryModule: memory sampled ≤1 per 30s window; values reused between samples.
- LocalTrafficAccountingModule: off→on transition re-anchors baseline, delta correct.
- Converters: encrypt→decrypt round-trip; legacy plaintext passthrough; null handling.
- LegacyMigration: throwing migration preserves legacy DB file.

## 7. Wrong vs Correct

### Wrong (infinite Binder retry)

```kotlin
while (true) {
    try { return client.block() }
    catch (e: DeadObjectException) { remote.reset() }  // spins forever if service is dead
}
```

### Correct (bounded, backed off)

```kotlin
var attempt = 0
while (true) {
    try { return client.block() }
    catch (e: DeadObjectException) {
        if (++attempt > MAX_RETRIES) throw e
        remote.reset()
        delay(RETRY_BASE_DELAY_MS * attempt)
    }
}
```

### Wrong (baseline reset to zero)

```kotlin
if (disabled) { lastUploadBytes = 0; lastDownloadBytes = 0; return }  // over-counts on re-enable
```

### Correct (stale baseline re-anchored)

```kotlin
if (disabled) { baselineDirty = true; return }
if (baselineDirty) { captureBaseline(); baselineDirty = false }
val (upload, download) = splitTrafficBytes(Clash.queryTrafficTotal())
// compute delta from fresh baseline
```

### Wrong (delete legacy DB on failure)

```kotlin
try { migrate() } catch (e: Exception) { log(e) }
context.deleteDatabase("clash-config")  // data lost if migrate() partially failed
```

### Correct (delete only on success)

```kotlin
try {
    migrate()
    context.deleteDatabase("clash-config")  // success path only
} catch (e: Exception) {
    log("keeping legacy DB for retry")
}
```
