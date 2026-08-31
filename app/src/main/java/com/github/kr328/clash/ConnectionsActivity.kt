package com.github.kr328.clash

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.model.ConnectionSnapshot
import com.github.kr328.clash.design.ConnectionsDesign
import com.github.kr328.clash.service.remote.IConnectionsObserver
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class ConnectionsActivity : BaseActivity<ConnectionsDesign>() {

    override suspend fun main() {
        val design = ConnectionsDesign(this)

        setContentDesign(design)

        val snapshots = Channel<ConnectionSnapshot>(Channel.CONFLATED)

        val observer = object : IConnectionsObserver {
            override fun newSnapshot(snapshot: ConnectionSnapshot) {
                snapshots.trySend(snapshot)
            }
        }

        suspend fun registerObserver() {
            runCatching {
                withClash { setConnectionsObserver(observer) }
            }.onFailure { Log.w("Failed to set connections observer", it) }
        }

        suspend fun unregisterObserver() {
            runCatching {
                withClash { setConnectionsObserver(null) }
            }.onFailure { Log.w("Failed to clear connections observer", it) }
        }

        try {
            while (isActive) {
                select<Unit> {
                    events.onReceive {
                        // B-84: keep the kernel-side subscription and the RecyclerView updates tied
                        // to visibility; a backgrounded page must not keep receiving snapshots.
                        when (it) {
                            Event.ActivityStart -> registerObserver()
                            Event.ActivityStop -> unregisterObserver()
                            else -> Unit
                        }
                    }
                    snapshots.onReceive { snapshot ->
                        design.updateConnections(snapshot.connections)
                    }
                    design.requests.onReceive { request ->
                        when (request) {
                            ConnectionsDesign.Request.CloseAll -> {
                                if (design.requestCloseAll()) {
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            withClash {
                                                closeAllConnections()
                                            }
                                        }.onFailure {
                                            Log.w("Failed to close all connections", it)
                                        }
                                    }
                                }
                            }
                            is ConnectionsDesign.Request.Close -> {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        withClash {
                                            closeConnection(request.id)
                                        }
                                    }.onFailure {
                                        Log.w("Failed to close connection", it)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            withContext(Dispatchers.IO + NonCancellable) {
                unregisterObserver()
            }
        }
    }
}
