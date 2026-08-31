package com.github.kr328.clash.log

import com.github.kr328.clash.core.model.LogMessage
import java.util.Date

internal data class ParsedLogLine(
    val message: LogMessage?,
    val lastTime: Date,
)

internal fun parseLogLine(line: String, lastTime: Date): ParsedLogLine {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        return ParsedLogLine(null, lastTime)
    }

    val fields = trimmed.split(":", limit = 3)
    val timestamp = fields.firstOrNull()?.toLongOrNull()
    val time = timestamp?.let(::Date) ?: lastTime
    val level = fields.getOrNull(1)?.let { raw ->
        LogMessage.Level.values().firstOrNull { it.name == raw }
    }
    val valid = timestamp != null && level != null && fields.size == 3
    val message = if (valid) {
        LogMessage(level = level!!, message = unescape(fields[2]), time = time)
    } else {
        LogMessage(level = LogMessage.Level.Warning, message = trimmed, time = time)
    }

    return ParsedLogLine(message, time)
}

/**
 * Reverses [LogcatWriter.escape]. Only well-formed records go through it: a malformed line was
 * not produced by our writer, so its backslashes are literal.
 */
internal fun unescape(message: String): String {
    if (!message.contains('\\')) return message

    return buildString(message.length) {
        var index = 0
        while (index < message.length) {
            val current = message[index]
            if (current != '\\' || index == message.lastIndex) {
                append(current)
                index += 1
                continue
            }

            when (val next = message[index + 1]) {
                'n' -> append('\n')
                'r' -> append('\r')
                '\\' -> append('\\')
                else -> {
                    append(current)
                    append(next)
                }
            }
            index += 2
        }
    }
}
