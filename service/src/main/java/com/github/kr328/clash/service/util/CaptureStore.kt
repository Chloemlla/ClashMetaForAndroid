package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded JSONL store for traffic capture data (DNS, connections, HTTP).
 *
 * Written to [captureDir] as a single JSONL file. Each line is a JSON object
 * with a `type` discriminator. Size- and duration-limited to prevent runaway
 * disk usage on the device.
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

    private val json = Json { encodeDefaults = false }

    @Volatile
    private var _activeFile: File? = null
    @Volatile
    private var _startedAt: Long = 0L
    @Volatile
    private var _maxDurationMs: Long = DEFAULT_DURATION_MS
    private val _isActive = AtomicBoolean(false)
    private val _eventChannel = Channel<String>(Channel.UNLIMITED)

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
        val fileName = "capture-${System.currentTimeMillis()}.jsonl"
        val file = File(dir, fileName)
        _activeFile = file
        _maxDurationMs = durationMs ?: DEFAULT_DURATION_MS
        _startedAt = System.currentTimeMillis()

        Log.i("$TAG: started → $fileName")

        // Launch the background writer coroutine.
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            writerLoop(file)
        }
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
    fun enqueue(type: String, payload: @Serializable Any) {
        if (!_isActive.get()) return
        val event = CaptureEvent(
            timestamp = System.currentTimeMillis(),
            type = type,
            data = json.encodeToString(payload),
        )
        _eventChannel.trySend(json.encodeToString(event))
    }

    private suspend fun writerLoop(file: File) = coroutineScope {
        val writer = file.bufferedWriter(Charsets.UTF_8)
        try {
            while (_isActive.get()) {
                // Check duration limit.
                if (System.currentTimeMillis() - _startedAt > _maxDurationMs) {
                    Log.i("$TAG: duration limit reached")
                    stop()
                    break
                }

                // Check file size limit.
                if (file.length() > MAX_FILE_SIZE) {
                    Log.i("$TAG: file size limit reached")
                    stop()
                    break
                }

                val line = _eventChannel.poll()
                if (line != null) {
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                } else {
                    // No events pending; yield briefly.
                    kotlinx.coroutines.delay(100)
                }
            }
        } catch (e: Exception) {
            Log.w("$TAG: writer error: ${e.message}", e)
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try { writer.close() } catch (_: Exception) {}
            }
        }
    }
}