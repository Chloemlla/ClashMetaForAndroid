package com.github.kr328.clash.service.store

import com.github.kr328.clash.service.clash.module.TrafficHistoryBuffer

/**
 * Process-local holder for the traffic history ring buffer.
 *
 * Memory-only in M1 (no disk). Cleared when the process dies; sampling restarts
 * with the Clash runtime. Not included in auto-backup.
 */
object TrafficHistoryStore {
    val buffer: TrafficHistoryBuffer = TrafficHistoryBuffer()
}
