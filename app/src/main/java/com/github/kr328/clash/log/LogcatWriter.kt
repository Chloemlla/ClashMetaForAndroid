package com.github.kr328.clash.log

import android.content.Context
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.util.logsDir
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class LogcatWriter(context: Context) : AutoCloseable {
    private val directory = context.logsDir
    private var writer: BufferedWriter
    private var writtenBytes = 0L
    private var messagesSinceFlush = 0

    init {
        directory.mkdirs()
        cleanupLogs()
        writer = openWriter(createLogFile())
    }

    override fun close() {
        writer.close()
    }

    fun appendMessage(message: LogMessage) {
        val boundedMessage = message.message.take(MAX_MESSAGE_CHARS)
        val line = FORMAT.format(message.time.time, message.level.name, boundedMessage) + '\n'
        val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size.toLong()

        if (writtenBytes > 0 && writtenBytes + lineBytes > MAX_FILE_BYTES) {
            rotate()
        }

        writer.append(line)
        writtenBytes += lineBytes
        messagesSinceFlush++

        if (messagesSinceFlush >= FLUSH_INTERVAL_MESSAGES) {
            writer.flush()
            messagesSinceFlush = 0
        }
    }

    private fun rotate() {
        writer.close()
        cleanupLogs()
        writer = openWriter(createLogFile())
        writtenBytes = 0
        messagesSinceFlush = 0
    }

    private fun openWriter(file: File): BufferedWriter {
        return BufferedWriter(OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8))
    }

    private fun createLogFile(): File {
        var timestamp = System.currentTimeMillis()
        var file = directory.resolve("clash-$timestamp.log")
        while (file.exists()) {
            timestamp++
            file = directory.resolve("clash-$timestamp.log")
        }
        return file
    }

    private fun cleanupLogs() {
        val retained = directory.listFiles()
            ?.asSequence()
            ?.filter(File::isFile)
            ?.filter { LOG_FILE.matches(it.name) }
            ?.sortedBy(File::lastModified)
            ?.toMutableList()
            ?: return
        var totalBytes = retained.sumOf(File::length)
        val maximumRetainedBytes = TOTAL_QUOTA_BYTES - MAX_FILE_BYTES
        val iterator = retained.iterator()

        while ((retained.size >= MAX_RETAINED_FILES || totalBytes > maximumRetainedBytes) && iterator.hasNext()) {
            val oldest = iterator.next()
            val length = oldest.length()
            if (oldest.delete()) {
                iterator.remove()
                totalBytes -= length
            }
        }
    }

    companion object {
        private const val FORMAT = "%d:%s:%s"
        private val LOG_FILE = Regex("clash-\\d+\\.log")

        const val MAX_FILE_BYTES = 8L * 1024 * 1024
        const val TOTAL_QUOTA_BYTES = 32L * 1024 * 1024
        const val MAX_RETAINED_FILES = 4
        const val FLUSH_INTERVAL_MESSAGES = 32
        const val MAX_MESSAGE_CHARS = 64 * 1024
    }
}
