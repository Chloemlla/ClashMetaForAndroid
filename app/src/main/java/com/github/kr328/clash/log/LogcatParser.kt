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
        LogMessage(level = level!!, message = fields[2], time = time)
    } else {
        LogMessage(level = LogMessage.Level.Warning, message = trimmed, time = time)
    }

    return ParsedLogLine(message, time)
}
