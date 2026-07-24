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

    fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride
    fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride)
    fun clearOverride(slot: Clash.OverrideSlot)

    fun setLogObserver(observer: ILogObserver?)
    fun setConnectionsObserver(observer: IConnectionsObserver?)

    fun closeConnection(id: String)
    fun closeAllConnections()
}
