package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.Proxy

fun List<Proxy>.filterByKeyword(keyword: String): List<Proxy> {
    val query = keyword.trim()
    if (query.isEmpty()) return this

    return filter { proxy ->
        proxy.name.contains(query, ignoreCase = true) ||
            proxy.title.contains(query, ignoreCase = true) ||
            proxy.subtitle.contains(query, ignoreCase = true) ||
            proxy.type.contains(query, ignoreCase = true)
    }
}

fun List<Proxy>.indexOfSelected(selectedName: String?): Int {
    if (selectedName.isNullOrEmpty()) return -1
    return indexOfFirst { it.name == selectedName }
}
