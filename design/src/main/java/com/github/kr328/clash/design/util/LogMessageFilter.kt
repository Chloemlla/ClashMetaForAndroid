package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.LogMessage

/** Case-insensitive keyword/tag predicate for the bounded logcat snapshot. */
fun LogMessage.matchesLogQuery(query: String): Boolean {
    val terms = query.trim().split(LOG_QUERY_WHITESPACE).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return true

    return terms.all { term ->
        message.contains(term, ignoreCase = true) ||
            level.name.contains(term, ignoreCase = true)
    }
}

private val LOG_QUERY_WHITESPACE = Regex("\\s+")
