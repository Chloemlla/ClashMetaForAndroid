package com.github.kr328.clash.service.clash.module

/**
 * One traffic history sample for sparklines / future widgets.
 *
 * Rates come from `queryTrafficNow` decode; totals from `queryTrafficTotal`.
 */
data class TrafficHistorySample(
    val epochMs: Long,
    val upRateBytesPerSec: Long,
    val downRateBytesPerSec: Long,
    val upTotalBytes: Long = 0L,
    val downTotalBytes: Long = 0L,
)

/**
 * Bounded in-memory ring buffer for traffic samples.
 *
 * Default capacity covers ~1h at ~5s (720 points). Dense samples denser than
 * [minIntervalMs] are rejected so producers can poll freely without growth.
 *
 * The interval gate runs on a caller-supplied monotonic clock, not on
 * [TrafficHistorySample.epochMs]: a wall-clock step backwards would otherwise
 * reject every sample until the wall clock caught up.
 */
class TrafficHistoryBuffer(
    capacity: Int = DEFAULT_CAPACITY,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
        require(minIntervalMs >= 0L) { "minIntervalMs must be >= 0" }
    }

    private val capacity: Int = capacity
    private val samples = arrayOfNulls<TrafficHistorySample>(capacity)
    private var head: Int = 0
    private var size: Int = 0
    private var lastAcceptedElapsedMs: Long = Long.MIN_VALUE / 2

    @Synchronized
    fun shouldAccept(elapsedMs: Long, minIntervalMs: Long = this.minIntervalMs): Boolean {
        if (size == 0) return true
        return elapsedMs - lastAcceptedElapsedMs >= minIntervalMs
    }

    /**
     * Append [sample] if [elapsedMs] respects the min-interval gate.
     * When full, overwrites the oldest entry.
     *
     * @param elapsedMs monotonic reading (e.g. `SystemClock.elapsedRealtime()`)
     * @return true if accepted
     */
    @Synchronized
    fun tryAppend(sample: TrafficHistorySample, elapsedMs: Long): Boolean {
        if (!shouldAccept(elapsedMs)) {
            return false
        }
        samples[head] = sample
        head = (head + 1) % capacity
        if (size < capacity) {
            size++
        }
        lastAcceptedElapsedMs = elapsedMs
        return true
    }

    /** Chronological snapshot (oldest → newest). */
    @Synchronized
    fun snapshot(): List<TrafficHistorySample> {
        if (size == 0) return emptyList()
        val result = ArrayList<TrafficHistorySample>(size)
        val start = if (size == capacity) head else 0
        for (i in 0 until size) {
            val idx = (start + i) % capacity
            samples[idx]?.let(result::add)
        }
        return result
    }

    @Synchronized
    fun size(): Int = size

    @Synchronized
    fun clear() {
        for (i in samples.indices) {
            samples[i] = null
        }
        head = 0
        size = 0
        lastAcceptedElapsedMs = Long.MIN_VALUE / 2
    }

    companion object {
        const val DEFAULT_CAPACITY = 720
        const val DEFAULT_MIN_INTERVAL_MS = 2000L
    }
}
