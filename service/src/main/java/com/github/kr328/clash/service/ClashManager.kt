package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IConnectionsObserver
import com.github.kr328.clash.service.remote.IAdblockObserver
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.scene.NodeFailoverController
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendOverrideChanged
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel

class ClashManager(private val context: Context) : IClashManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)
    private val failover = NodeFailoverController(context)
    private var logReceiver: ReceiveChannel<LogMessage>? = null
    private var connectionsReceiver: ReceiveChannel<com.github.kr328.clash.core.model.ConnectionSnapshot>? = null
    private var adblockReceiver: ReceiveChannel<com.github.kr328.clash.core.model.AdblockHit>? = null

    override fun queryTunnelState(): TunnelState {
        return Clash.queryTunnelState()
    }

    override fun queryTrafficTotal(): Long {
        return Clash.queryTrafficTotal()
    }

    override fun queryTrafficNow(): Long {
        return Clash.queryTrafficNow()
    }

    override fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String> {
        return Clash.queryGroupNames(excludeNotSelectable)
    }

    override fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup {
        return Clash.queryGroup(name, proxySort)
    }

    override fun queryProxyGroupNow(name: String): String {
        return Clash.queryGroupNow(name)
    }

    override fun queryProxyGroupDelays(name: String): Map<String, Int> {
        return Clash.queryGroupDelays(name)
    }

    override fun queryConfiguration(): UiConfiguration {
        return Clash.queryConfiguration()
    }

    override fun queryProviders(): ProviderList {
        return ProviderList(Clash.queryProviders())
    }

    override fun hasProviders(): Boolean {
        return Clash.hasProviders()
    }

    override fun queryDashboardSummary(
        preferred: String,
        excludeNotSelectable: Boolean,
    ): DashboardSummary {
        return Clash.queryDashboardSummary(preferred, excludeNotSelectable)
    }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride {
        return Clash.queryOverride(slot)
    }

    override fun patchSelector(group: String, name: String): Boolean {
        return Clash.patchSelector(group, name).also {
            val current = store.activeProfile ?: return@also

            if (it) {
                SelectionDao().setSelected(Selection(current, group, name))
            } else {
                SelectionDao().removeSelected(current, group)
            }
        }
    }

    override fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride) {
        Clash.patchOverride(slot, configuration)

        context.sendOverrideChanged()
    }

    override fun clearOverride(slot: Clash.OverrideSlot) {
        Clash.clearOverride(slot)
    }

    override suspend fun healthCheck(group: String) {
        try {
            Clash.healthCheck(group).await()
            evaluateFailover(group, completedSuccessfully = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            evaluateFailover(group, completedSuccessfully = false)
            throw e
        }
    }

    private suspend fun evaluateFailover(group: String, completedSuccessfully: Boolean) {
        try {
            failover.onHealthCheckCompleted(group, completedSuccessfully)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Node failover evaluation failed for group=$group", e)
        }
    }

    override suspend fun updateProvider(type: Provider.Type, name: String) {
        return Clash.updateProvider(type, name).await()
    }

    override suspend fun updateAdblock(proxy: String?) {
        val activeProfile = store.activeProfile
            ?: throw IllegalStateException("No active profile")

        // Download the MRS file into the active profile dir. proxy == null routes
        // through the running tunnel's default outbound; a group/node name forces
        // that specific outbound.
        Clash.updateAdblock(context.importedDir.resolve(activeProfile.toString()), proxy).await()

        // The loaded config likely carries the empty inline placeholder (the file
        // was missing when it was loaded), so reload to swap in the real HTTP
        // provider, which then reads the freshly-downloaded local file.
        context.sendProfileChanged(activeProfile)
    }

    override fun isAdblockRulesReady(): Boolean {
        val activeProfile = store.activeProfile ?: return false

        return Clash.adblockReady(context.importedDir.resolve(activeProfile.toString()))
    }

    override fun setLogObserver(observer: ILogObserver?) {
        synchronized(this) {
            logReceiver?.apply {
                // GC + FreeOSMemory are expensive (STW + memory return); they happen once in
                // this cancelled coroutine's finally after the channel drains, not here.
                cancel()
            }

            if (observer != null) {
                logReceiver = Clash.subscribeLogcat().also { c ->
                    launch {
                        try {
                            while (isActive) {
                                observer.newItem(c.receive())
                            }
                        } catch (e: CancellationException) {
                            // intended behavior
                            // ignore
                        } catch (e: Exception) {
                            Log.w("UI crashed", e)
                        } finally {
                            withContext(NonCancellable) {
                                c.cancel()

                                Clash.forceGc()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun setConnectionsObserver(observer: IConnectionsObserver?) {
        synchronized(this) {
            connectionsReceiver?.apply {
                // GC + FreeOSMemory happen once in this cancelled coroutine's finally after
                // the channel drains, not here.
                cancel()
            }
            connectionsReceiver = null

            if (observer != null) {
                connectionsReceiver = Clash.subscribeConnections(1000L).also { c ->
                    launch {
                        try {
                            while (isActive) {
                                observer.newSnapshot(c.receive())
                            }
                        } catch (e: CancellationException) {
                            // intended behavior
                        } catch (e: Exception) {
                            Log.w("connections observer crashed", e)
                        } finally {
                            withContext(NonCancellable) {
                                c.cancel()

                                Clash.forceGc()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun queryAdblockStats(): AdblockStats {
        return Clash.queryAdblockStats()
    }

    override fun clearAdblockHits() {
        Clash.clearAdblockHits()
    }

    override fun setAdblockObserver(observer: IAdblockObserver?) {
        synchronized(this) {
            adblockReceiver?.apply {
                cancel()
            }
            adblockReceiver = null

            if (observer != null) {
                adblockReceiver = Clash.subscribeAdblock().also { c ->
                    launch {
                        try {
                            while (isActive) {
                                // Ad-heavy pages burst faster than a Binder round trip per hit
                                // can keep up with, so drain the whole upstream queue, keep only
                                // the newest hits and forward them in one batch per window.
                                // Exact totals come from queryAdblockStats, not from this stream.
                                val batch = ArrayDeque<AdblockHit>()

                                batch.addLast(c.receive())

                                while (true) {
                                    batch.addLast(c.tryReceive().getOrNull() ?: break)

                                    if (batch.size > ADBLOCK_BATCH_SIZE) {
                                        batch.removeFirst()
                                    }
                                }

                                batch.forEach { observer.onHit(it) }

                                delay(ADBLOCK_BATCH_INTERVAL)
                            }
                        } catch (e: CancellationException) {
                            // intended behavior
                        } catch (e: Exception) {
                            Log.w("adblock observer crashed", e)
                        } finally {
                            withContext(NonCancellable) {
                                c.cancel()

                                Clash.forceGc()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun closeConnection(id: String) {
        Clash.closeConnection(id)
    }

    override fun closeAllConnections() {
        Clash.closeAllConnections()
    }

    companion object {
        private const val ADBLOCK_BATCH_SIZE = 64
        private const val ADBLOCK_BATCH_INTERVAL = 500L
    }
}
