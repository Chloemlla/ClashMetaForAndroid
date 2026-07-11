package com.github.kr328.clash.log

import android.content.Context
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.util.logsDir
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.*

class LogcatReader(
    context: Context,
    file: LogFile,
    maxReadBytes: Long? = MAX_READ_BYTES,
) : AutoCloseable {
    private val input = FileInputStream(context.logsDir.resolve(file.fileName))
    private val reader: BufferedReader

    init {
        val start = maxReadBytes
            ?.let { (input.channel.size() - it).coerceAtLeast(0) }
            ?: 0
        input.channel.position(start)
        reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))

        if (start > 0) {
            reader.readLine()
        }
    }

    override fun close() {
        reader.close()
    }

    fun readAll(): List<LogMessage> {
        val messages = ArrayDeque<LogMessage>(MAX_MESSAGES)

        forEachMessage { message ->
            if (messages.size >= MAX_MESSAGES) {
                messages.removeFirst()
            }
            messages.addLast(message)
        }

        return messages.toList()
    }

    fun forEachMessage(action: (LogMessage) -> Unit) {
        var lastTime = Date(0)

        reader.forEachLine { line ->
            val parsed = parseLogLine(line, lastTime)
            lastTime = parsed.lastTime
            val message = parsed.message ?: return@forEachLine
            action(message)
        }
    }

    companion object {
        const val MAX_MESSAGES = 5_000
        const val MAX_READ_BYTES = 4L * 1024 * 1024
    }
}
