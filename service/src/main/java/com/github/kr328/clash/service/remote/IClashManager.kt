package com.github.kr328.clash.service.remote

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.kaidl.BinderInterface

@BinderInterface
interface IClashManager {
    fun queryTunnelState(): TunnelState
    fun queryTrafficTotal(): Long
    fun queryTrafficNow(): Long
    fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String>
    fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup
    /** Selected proxy name only (no full member list). */
    fun queryProxyGroupNow(name: String): String
    /** name → last delay ms; intermediate URL-test polls. */
    fun queryProxyGroupDelays(name: String): Map<String, Int>
    fun queryConfiguration(): UiConfiguration
    fun queryProviders(): ProviderList
    /** True when any non-compatible provider is loaded. */
    fun hasProviders(): Boolean
    /** Compact main-screen mode + providers + selected node. */
    fun queryDashboardSummary(preferred: String, excludeNotSelectable: Boolean): DashboardSummary

    fun patchSelector(group: String, name: String): Boolean

    suspend fun healthCheck(group: String)
    suspend fun updateProvider(type: Provider.Type, name: String)
    /**
     * Update the built-in adblock rule-set. When [proxy] is non-empty the download
     * is routed through that outbound (a proxy group/node of the loaded config);
     * null falls back to the running tunnel's default routing. Works even when the
     * tunnel is idle.
     */
    suspend fun updateAdblock(proxy: String? = null)
    /** True when the active profile already has the built-in adblock MRS file. */
    fun isAdblockRulesReady(): Boolean

    fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride
    fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride)
    fun clearOverride(slot: Clash.OverrideSlot)

    fun setLogObserver(observer: ILogObserver?)
    fun setConnectionsObserver(observer: IConnectionsObserver?)
    fun setAdblockObserver(observer: IAdblockObserver?)
    fun queryAdblockStats(): AdblockStats
    fun clearAdblockHits()

    fun closeConnection(id: String)
    fun closeAllConnections()
}
