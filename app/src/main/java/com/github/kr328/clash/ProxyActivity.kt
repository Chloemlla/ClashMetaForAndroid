package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ProxyActivity : BaseActivity<ProxyDesign>() {
    override suspend fun main() {
        // B-86: the three parallel arrays (names / states / scrolledToSelected) are merged into one
        // list of ProxyGroupState so an index can never silently drift out of sync with its group.
        val (mode, names) = try {
            val fetchedMode = withClash { queryOverride(Clash.OverrideSlot.Session).mode }
            val fetchedNames = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
            fetchedMode to fetchedNames
        } catch (e: Exception) {
            // A-35: show the empty skeleton (visible page, not a blank window) and let
            // BaseActivity surface the error toast.
            setContentDesign(ProxyDesign(this, null, emptyList(), uiStore))
            throw e
        }
        val groups = List(names.size) { ProxyGroupState(names[it]) }
        val states = groups.map { it.state }
        val unorderedStates = groups.associate { it.name to it.state }
        val reloadLock = Semaphore(MAX_CONCURRENT_GROUP_LOADS)

        val design = ProxyDesign(this, mode, names, uiStore)

        setContentDesign(design)

        suspend fun reloadGroup(
            index: Int,
            animateDelay: Boolean = false,
            completeUrlTest: Boolean = true,
            preserveOrder: Boolean = false,
        ) {
            val group = groups[index]
            val groupName = group.name
            val reloaded = reloadLock.withPermit {
                withClash {
                    queryProxyGroup(groupName, uiStore.proxySort)
                }
            }
            val state = group.state
            val selectionChanged = state.now != reloaded.now
            val shouldScroll = !group.scrolledToSelected && reloaded.now.isNotEmpty()

            state.now = reloaded.now

            design.updateGroup(
                index,
                reloaded.proxies,
                reloaded.type == "Selector",
                state,
                unorderedStates,
                animateDelay,
                completeUrlTest,
                preserveOrder,
                selectionChanged,
                shouldScroll,
            )

            if (shouldScroll) {
                group.scrolledToSelected = true
            }
        }

        design.requests.send(ProxyDesign.Request.ReloadAll)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ProfileLoaded -> {
                            val newNames = withClash {
                                queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                            }

                            if (newNames != names) {
                                startActivity(ProxyActivity::class.intent)

                                finish()
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProxyDesign.Request.ReLaunch -> {
                            startActivity(ProxyActivity::class.intent)

                            finish()
                        }
                        ProxyDesign.Request.ReloadAll -> {
                            names.indices.forEach { idx ->
                                design.requests.trySend(ProxyDesign.Request.Reload(idx))
                            }
                        }
                        is ProxyDesign.Request.Reload -> {
                            launch {
                                reloadGroup(it.index)
                            }
                        }
                        is ProxyDesign.Request.Select -> {
                            val group = groups[it.index]
                            withClash {
                                patchSelector(group.name, it.name)

                                group.state.now = it.name
                            }

                            design.notifySelectionChanged(it.index)
                        }
                        is ProxyDesign.Request.UrlTest -> {
                            launch {
                                val groupName = groups[it.index].name
                                // Ensure the page has a full dataset before delay-only patches.
                                reloadGroup(
                                    it.index,
                                    animateDelay = false,
                                    completeUrlTest = false,
                                    preserveOrder = false,
                                )

                                val refreshJob = launch {
                                    while (isActive) {
                                        delay(URL_TEST_REFRESH_INTERVAL_MILLIS)

                                        // Intermediate polls: delay map only (no full group JSON).
                                        val delays = reloadLock.withPermit {
                                            withClash { queryProxyGroupDelays(groupName) }
                                        }
                                        design.patchDelays(it.index, delays, animateDelay = true)
                                    }
                                }

                                try {
                                    withClash {
                                        healthCheck(groupName)
                                    }
                                } finally {
                                    refreshJob.cancelAndJoin()

                                    // Final full reload for sort/selection consistency.
                                    reloadGroup(
                                        it.index,
                                        animateDelay = true,
                                        completeUrlTest = true,
                                    )
                                }
                            }
                        }
                        is ProxyDesign.Request.PatchMode -> {
                            design.showModeSwitchTips()

                            withClash {
                                val o = queryOverride(Clash.OverrideSlot.Session)

                                o.mode = it.mode

                                patchOverride(Clash.OverrideSlot.Session, o)
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        // Intermediate full-group polls during URL test are expensive (JSON/JNI/Binder).
        // 1s is enough for live delay feedback; a final full reload still runs on completion.
        const val URL_TEST_REFRESH_INTERVAL_MILLIS = 1000L

        // B-86: concurrent group reloads are bounded. 10 was the historical magic number — a full
        // proxy-group JSON parse + Binder round-trip is heavy, but most configs have far fewer
        // groups, and allowing all groups to reload at once would spike memory on large configs.
        const val MAX_CONCURRENT_GROUP_LOADS = 10
    }
}

/** Per-group mutable UI state; keeps name, selection, and scroll progress together (B-86). */
private class ProxyGroupState(val name: String) {
    val state = com.github.kr328.clash.design.model.ProxyState("?")
    var scrolledToSelected = false
}

