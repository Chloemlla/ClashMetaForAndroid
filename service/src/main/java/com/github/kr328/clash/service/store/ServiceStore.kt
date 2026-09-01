package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AccessControlMode
import java.util.*

class ServiceStore(context: Context) {
    private val preferences = PreferenceProvider.createSharedPreferencesFromContext(context)
    private val store = Store(preferences.asStoreProvider())
    private val subscriptionTrafficBilling = SubscriptionTrafficBillingPreference(
        contains = { key -> preferences.contains(key) },
        readBoolean = { key, defaultValue -> preferences.getBoolean(key, defaultValue) },
        writeBoolean = { key, value ->
            preferences.edit()
                .putBoolean(key, value)
                .apply()
        },
        remove = { key ->
            preferences.edit()
                .remove(key)
                .apply()
        },
    )

    var activeProfile: UUID? by store.typedString(
        key = "active_profile",
        // Parse defensively: a corrupt or migration-merged non-UUID value degrades to
        // "no active profile" instead of throwing on every read of activeProfile.
        from = { if (it.isBlank()) null else runCatching { UUID.fromString(it) }.getOrNull() },
        to = { it?.toString() ?: "" }
    )

    var bypassPrivateNetwork: Boolean by store.boolean(
        key = "bypass_private_network",
        defaultValue = true
    )

    var accessControlMode: AccessControlMode by store.enum(
        key = "access_control_mode",
        defaultValue = AccessControlMode.AcceptAll,
        values = AccessControlMode.values()
    )

    var accessControlPackages by store.stringSet(
        key = "access_control_packages",
        defaultValue = emptySet()
    )

    var dnsHijacking by store.boolean(
        key = "dns_hijacking",
        defaultValue = true
    )

    var systemProxy by store.boolean(
        key = "system_proxy",
        defaultValue = true
    )

    var allowBypass by store.boolean(
        key = "allow_bypass",
        defaultValue = true
    )

    /**
     * When true (default), installed partner packages (PiliPlus / NexAI /
     * Project-Lumen / Zhihu++) are auto-kept in VPN access control (allow on
     * AcceptSelected / never deny on DenySelected).
     *
     * Preference key kept as `pili_plus_auto_adapt` for upgrade compatibility.
     */
    var partnerAppAutoAdapt by store.boolean(
        key = "pili_plus_auto_adapt",
        defaultValue = true
    )

    var allowIpv6 by store.boolean(
        key = "allow_ipv6",
        defaultValue = false
    )

    var tunStackMode by store.string(
        key = "tun_stack_mode",
        defaultValue = "system"
    )

    var dynamicNotification by store.boolean(
        key = "dynamic_notification",
        defaultValue = true
    )

    /**
     * Default and legacy migration seed for profiles without a scoped choice.
     * Profile/runtime callers must use [getLocalSubscriptionTraffic] instead.
     */
    var localSubscriptionTraffic by store.boolean(
        key = "local_subscription_traffic",
        defaultValue = true
    )

    /**
     * Returns the billing mode owned by [uuid]. Existing installs lazily copy the
     * former global value into the profile-scoped key on first access.
     */
    fun getLocalSubscriptionTraffic(uuid: UUID): Boolean {
        return subscriptionTrafficBilling.get(uuid, localSubscriptionTraffic)
    }

    internal fun getLocalSubscriptionTrafficIfPresent(uuid: UUID): Boolean? {
        return subscriptionTrafficBilling.getIfPresent(uuid, localSubscriptionTraffic)
    }

    fun setLocalSubscriptionTraffic(uuid: UUID, enabled: Boolean) {
        subscriptionTrafficBilling.set(uuid, enabled)
    }

    fun clearLocalSubscriptionTraffic(uuid: UUID) {
        subscriptionTrafficBilling.clear(uuid)
    }

    /**
     * When true (default), post a local notification once when a URL profile's
     * expire is within 3 days or already past due.
     */
    var subscriptionExpiryReminders by store.boolean(
        key = "subscription_expiry_reminders",
        defaultValue = true
    )

    /**
     * Keys already notified for subscription expiry (`uuid|bucket|expireMs`).
     * Prevents spam loops across worker runs.
     *
     * Every renewal mints a new `expireMs`, so writes keep only the newest expiry per
     * profile — otherwise the set grows for the lifetime of the install.
     */
    var subscriptionExpiryNotifiedKeys: Set<String>
        get() = notifiedExpiryKeys
        set(value) {
            notifiedExpiryKeys = retainLatestExpiryKeys(value)
        }

    private var notifiedExpiryKeys by store.stringSet(
        key = "subscription_expiry_notified_keys",
        defaultValue = emptySet()
    )

    var autoScenesEnabled by store.boolean(
        key = "auto_scenes_enabled",
        defaultValue = false,
    )

    var sceneNotificationsEnabled by store.boolean(
        key = "scene_notifications_enabled",
        defaultValue = true,
    )

    var sceneSsidMatchingEnabled by store.boolean(
        key = "scene_ssid_matching_enabled",
        defaultValue = false,
    )

    internal var scenesJson by store.string(
        key = "scenes_json",
        defaultValue = "",
    )

    var autoFailoverEnabled by store.boolean(
        key = "auto_failover_enabled",
        defaultValue = false,
    )

    var failoverNotificationsEnabled by store.boolean(
        key = "failover_notifications_enabled",
        defaultValue = true,
    )

    var failoverThreshold by store.int(
        key = "failover_threshold",
        defaultValue = 3,
    )

    var failoverCooldownMillis by store.long(
        key = "failover_cooldown_millis",
        defaultValue = 60_000L,
    )

    var failoverSort by store.enum(
        key = "failover_sort",
        defaultValue = ProxySort.Default,
        values = ProxySort.values(),
    )

    /**
     * Set when the legacy-database migration threw and the old database was kept for a retry.
     * Written from the migration (in the `:background` process) and cleared by the UI once the user
     * has been told — an empty profile list is otherwise indistinguishable from a total loss (A-39).
     */
    var legacyMigrationFailed by store.boolean(
        key = "legacy_migration_failed",
        defaultValue = false,
    )

    private fun retainLatestExpiryKeys(keys: Set<String>): Set<String> {
        val latest = HashMap<String, Long>()
        for (key in keys) {
            val (uuid, expire) = splitExpiryKey(key) ?: continue
            if (expire > (latest[uuid] ?: Long.MIN_VALUE)) {
                latest[uuid] = expire
            }
        }
        return keys.filterTo(HashSet()) { key ->
            val parsed = splitExpiryKey(key)
            parsed != null && latest[parsed.first] == parsed.second
        }
    }

    private fun splitExpiryKey(key: String): Pair<String, Long>? {
        val parts = key.split('|')
        if (parts.size != 3) return null
        val expire = parts[2].toLongOrNull() ?: return null
        return parts[0] to expire
    }
}
