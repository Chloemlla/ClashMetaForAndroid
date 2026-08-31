package com.github.kr328.clash

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.constants.Adblock
import com.github.kr328.clash.core.model.AdblockHit
import com.github.kr328.clash.design.AdblockHitsDesign
import com.github.kr328.clash.service.remote.IAdblockObserver
import com.github.kr328.clash.util.clashDir
import com.github.kr328.clash.util.logsDir
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AdblockHitsActivity : BaseActivity<AdblockHitsDesign>() {

    override suspend fun main() {
        val design = AdblockHitsDesign(this)

        setContentDesign(design)

        val hits = Channel<AdblockHit>(Channel.UNLIMITED)

        val observer = object : IAdblockObserver {
            override fun onHit(hit: AdblockHit) {
                hits.trySend(hit)
            }
        }

        withContext(Dispatchers.IO) {
            runCatching {
                withClash {
                    setAdblockObserver(observer)
                }
            }.onFailure { Log.w("Failed to set adblock observer", it) }
        }

        launch {
            while (isActive) {
                runCatching {
                    withClash {
                        queryAdblockStats()
                    }
                }.onSuccess { design.setStats(it) }
                    .onFailure { Log.w("queryAdblockStats failed", it) }

                delay(2000)
            }
        }

        try {
            while (isActive) {
                select<Unit> {
                    events.onReceive {
                        when (it) {
                            Event.ActivityStart -> {
                                val history = withContext(Dispatchers.IO) {
                                    loadHistory()
                                }

                                design.patchHits(history)
                            }
                            else -> Unit
                        }
                    }
                    hits.onReceive { hit ->
                        design.appendHit(hit)
                    }
                    design.requests.onReceive {
                        when (it) {
                            AdblockHitsDesign.Request.Clear -> {
                                val target = design.requestClearTarget()
                                if (target != null) {
                                    withContext(Dispatchers.IO) {
                                        when (target) {
                                            AdblockHitsDesign.ClearTarget.Hits ->
                                                runCatching { withClash { clearAdblockHits() } }
                                            AdblockHitsDesign.ClearTarget.Logs ->
                                                runCatching { logsDir.deleteRecursively() }
                                            AdblockHitsDesign.ClearTarget.All -> {
                                                runCatching { withClash { clearAdblockHits() } }
                                                runCatching { logsDir.deleteRecursively() }
                                            }
                                        }
                                    }

                                    design.clearRecords()
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            withContext(Dispatchers.IO + NonCancellable) {
                runCatching {
                    withClash {
                        setAdblockObserver(null)
                    }
                }
            }
        }
    }

    private fun loadHistory(): List<AdblockHit> {
        val file = clashDir.resolve(Adblock.HITS_FILE_NAME)

        if (!file.exists()) {
            return emptyList()
        }

        return file.readLines().mapNotNull { line ->
            runCatching {
                Json.Default.decodeFromString(AdblockHit.serializer(), line.trim())
            }.getOrNull()
        }
    }
}
