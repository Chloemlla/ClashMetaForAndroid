package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AccessControlMode
import java.util.*

class ServiceStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
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
     * When true (default), config-page traffic is billed from 0 B via
     * LocalSubscriptionTrafficStore and upstream subscription-userinfo is ignored.
     * When false, upload/download/total/expire come from subscription-userinfo.
     */
    var localSubscriptionTraffic by store.boolean(
        key = "local_subscription_traffic",
        defaultValue = true
    )
}
