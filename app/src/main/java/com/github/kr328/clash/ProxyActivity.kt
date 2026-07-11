package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.model.ProxyState
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
        val mode = withClash { queryOverride(Clash.OverrideSlot.Session).mode }
        val names = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
        val states = List(names.size) { ProxyState("?") }
        val unorderedStates = names.indices.map { names[it] to states[it] }.toMap()
        val reloadLock = Semaphore(10)

        val design = ProxyDesign(
            this,
            mode,
            names,
            uiStore
        )

        setContentDesign(design)

        suspend fun reloadGroup(
            index: Int,
            animateDelay: Boolean = false,
            completeUrlTest: Boolean = true,
            preserveOrder: Boolean = false,
        ) {
            val group = reloadLock.withPermit {
                withClash {
                    queryProxyGroup(names[index], uiStore.proxySort)
                }
            }
            val state = states[index]
            val selectionChanged = state.now != group.now

            state.now = group.now

            design.updateGroup(
                index,
                group.proxies,
                group.type == "Selector",
                state,
                unorderedStates,
                animateDelay,
                completeUrlTest,
                preserveOrder,
                selectionChanged,
            )
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
                            withClash {
                                patchSelector(names[it.index], it.name)

                                states[it.index].now = it.name
                            }

                            design.notifySelectionChanged(it.index)
                        }
                        is ProxyDesign.Request.UrlTest -> {
                            launch {
                                val refreshJob = launch {
                                    while (isActive) {
                                        delay(URL_TEST_REFRESH_INTERVAL_MILLIS)

                                        reloadGroup(
                                            it.index,
                                            animateDelay = true,
                                            completeUrlTest = false,
                                            preserveOrder = true,
                                        )
                                    }
                                }

                                try {
                                    withClash {
                                        healthCheck(names[it.index])
                                    }
                                } finally {
                                    refreshJob.cancelAndJoin()

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
        const val URL_TEST_REFRESH_INTERVAL_MILLIS = 400L
    }
}
