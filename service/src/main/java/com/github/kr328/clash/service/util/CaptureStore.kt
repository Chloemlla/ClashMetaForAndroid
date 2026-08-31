package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded JSONL store for traffic capture data (DNS, connections, HTTP).
 *
 * Written to the app's internal `capture` directory as a single JSONL file per session, bounded by
 * size and duration. Captures contain DNS queries and connection metadata — effectively the user's
 * browsing history in plaintext — so old sessions are pruned on every start rather than kept.
 */
object CaptureStore {

    /**
     * Generic capture event envelope. [type] discriminates the payload schema;
     * [data] is the JSON-serialized payload object.
     */
    @Serializable
    data class CaptureEvent(
        val timestamp: Long,
        val type: String,
        val data: String,
    )

    private const val TAG = "CaptureStore"
    private const val MAX_FILE_SIZE = 10L * 1024L * 1024L // 10 MiB
    private const val DEFAULT_DURATION_MS = 60_000L // 60 s
    private const val MAX_RETAINED_SESSIONS = 3
    private const val FLUSH_INTERVAL_MS = 500L

    // Capture is diagnostic: under a traffic burst the producers are the kernel's DNS/connection
    // hot paths, so dropping the oldest queued lines is the only acceptable back-pressure. An
    // unbounded channel would grow the :background heap until the process is killed.
    private const val EVENT_QUEUE_CAPACITY = 4096

    private val json = Json { encodeDefaults = false }

    @Volatile
    private var _activeFile: File? = null
    @Volatile
    private var _startedAt: Long = 0L
    @Volatile
    private var _maxDurationMs: Long = DEFAULT_DURATION_MS
    private val _isActive = AtomicBoolean(false)
    private val _eventChannel = Channel<String>(
        capacity = EVENT_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writerJob: Job? = null

    /** Whether capture is currently running. */
    val isActive: Boolean get() = _isActive.get()

    /** Path to the current capture file, or null if inactive. */
    val activeFilePath: String? get() = _activeFile?.absolutePath

    /**
     * Start capturing into [ctx]'s internal capture directory.
     * If [durationMs] is set, capture auto-stops after that many milliseconds.
     */
    fun start(ctx: Context, durationMs: Long? = null) {
        if (!_isActive.compareAndSet(false, true)) {
            Log.w("$TAG: capture already active")
            return
        }

        val dir = File(ctx.filesDir, "capture").also { it.mkdirs() }

        pruneOldSessions(dir)

        // A previous writer can still be parked in its flush timeout; once _isActive flips back to
        // true it would resume writing to the old file and steal this session's lines.
        writerJob?.cancel()

        // A previous session's tail can still sit in the channel; it must not bleed into this file.
        var drained = _eventChannel.tryReceive()
        while (drained.isSuccess) {
            drained = _eventChannel.tryReceive()
        }

        val fileName = "capture-${System.currentTimeMillis()}.jsonl"
        val file = File(dir, fileName)
        _activeFile = file
        _maxDurationMs = durationMs ?: DEFAULT_DURATION_MS
        _startedAt = System.currentTimeMillis()

        Log.i("$TAG: started → $fileName")

        writerJob = scope.launch { writerLoop(file) }
    }

    /** Stop capture and close the current file. */
    fun stop() {
        if (!_isActive.compareAndSet(true, false)) return
        Log.i("$TAG: stopped, wrote ${_activeFile?.length() ?: 0} bytes")
        _activeFile = null
    }

    /**
     * Enqueue a [CaptureEvent] for writing. Thread-safe, non-blocking.
     * Drops the event if capture is not active.
     */
    fun <T> enqueue(type: String, serializer: KSerializer<T>, payload: T) {
        if (!_isActive.get()) return
        val event = CaptureEvent(
            timestamp = System.currentTimeMillis(),
            type = type,
            data = json.encodeToString(serializer, payload),
        )
        _eventChannel.trySend(json.encodeToString(CaptureEvent.serializer(), event))
    }

    private fun pruneOldSessions(dir: File) {
        val sessions = dir.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }
            ?: return

        sessions.sortedByDescending { it.lastModified() }
            .drop(MAX_RETAINED_SESSIONS - 1)
            .forEach { stale ->
                if (!stale.delete()) {
                    Log.w("$TAG: unable to prune ${stale.name}")
                }
            }
    }

    private suspend fun writerLoop(file: File) {
        val writer = file.bufferedWriter(Charsets.UTF_8)
        try {
            while (_isActive.get()) {
                if (System.currentTimeMillis() - _startedAt > _maxDurationMs) {
                    Log.i("$TAG: duration limit reached")
                    stop()
                    break
                }

                if (file.length() > MAX_FILE_SIZE) {
                    Log.i("$TAG: file size limit reached")
                    stop()
                    break
                }

                // Suspend rather than spin: the timeout exists only so the two limits above are
                // still re-checked during a lull. A lull is also the cheapest moment to flush, so
                // bursts batch into the buffer instead of paying a write syscall per line.
                val line = withTimeoutOrNull(FLUSH_INTERVAL_MS) { _eventChannel.receive() }
                if (line == null) {
                    writer.flush()
                    continue
                }

                writer.write(line)
                writer.newLine()
            }
        } catch (e: Exception) {
            Log.w("$TAG: writer error: ${e.message}", e)
        } finally {
            withContext(NonCancellable) {
                runCatching { writer.close() }
            }
        }
    }
}
