package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.Connection
import java.util.Locale

enum class ConnectionGrouping {
    App,
    Host,
    Chain,
}

enum class ConnectionAggregateSort {
    Bytes,
    Count,
}

data class ConnectionAggregate(
    val grouping: ConnectionGrouping,
    val key: String,
    val title: String,
    val subtitle: String,
    val count: Int,
    val upload: Long,
    val download: Long,
) {
    val totalBytes: Long
        get() = upload + download
}

fun aggregateConnections(
    connections: List<Connection>,
    grouping: ConnectionGrouping,
    sort: ConnectionAggregateSort,
    appLabels: Map<String, String> = emptyMap(),
): List<ConnectionAggregate> {
    val groups = LinkedHashMap<String, MutableConnectionAggregate>()

    for (connection in connections) {
        val descriptor = connection.groupDescriptor(grouping, appLabels)
        val aggregate = groups.getOrPut(descriptor.key) {
            MutableConnectionAggregate(
                title = descriptor.title,
                subtitle = descriptor.subtitle,
            )
        }
        aggregate.count++
        aggregate.upload += connection.upload.coerceAtLeast(0L)
        aggregate.download += connection.download.coerceAtLeast(0L)
    }

    val comparator = when (sort) {
        ConnectionAggregateSort.Bytes ->
            compareByDescending<ConnectionAggregate> { it.totalBytes }
                .thenByDescending { it.count }
        ConnectionAggregateSort.Count ->
            compareByDescending<ConnectionAggregate> { it.count }
                .thenByDescending { it.totalBytes }
    }.thenBy { it.title.lowercase(Locale.ROOT) }

    return groups.map { (key, aggregate) ->
        ConnectionAggregate(
            grouping = grouping,
            key = key,
            title = aggregate.title,
            subtitle = aggregate.subtitle,
            count = aggregate.count,
            upload = aggregate.upload,
            download = aggregate.download,
        )
    }.sortedWith(comparator)
}

fun filterConnections(connections: List<Connection>, query: String): List<Connection> {
    val terms = query.searchTerms()
    if (terms.isEmpty()) return connections

    return connections.filter { connection ->
        val fields = listOf(
            connection.host,
            connection.dstIp,
            connection.dstPort,
            connection.network,
            connection.type,
            connection.process,
            connection.packageName,
            connection.uid.takeIf { it != 0 }?.toString().orEmpty(),
            connection.chains,
            connection.rule,
            connection.rulePayload,
        )
        terms.all { term -> fields.any { it.contains(term, ignoreCase = true) } }
    }
}

fun filterConnectionAggregates(
    aggregates: List<ConnectionAggregate>,
    query: String,
): List<ConnectionAggregate> {
    val terms = query.searchTerms()
    if (terms.isEmpty()) return aggregates

    return aggregates.filter { aggregate ->
        terms.all { term ->
            aggregate.title.contains(term, ignoreCase = true) ||
                aggregate.subtitle.contains(term, ignoreCase = true)
        }
    }
}

private data class GroupDescriptor(
    val key: String,
    val title: String,
    val subtitle: String,
)

private data class MutableConnectionAggregate(
    val title: String,
    val subtitle: String,
    var count: Int = 0,
    var upload: Long = 0L,
    var download: Long = 0L,
)

private fun Connection.groupDescriptor(
    grouping: ConnectionGrouping,
    appLabels: Map<String, String>,
): GroupDescriptor {
    return when (grouping) {
        ConnectionGrouping.App -> appGroupDescriptor(appLabels)
        ConnectionGrouping.Host -> {
            val value = host.trim().ifEmpty { dstIp.trim() }
            GroupDescriptor(
                key = "host:${value.lowercase(Locale.ROOT)}",
                title = value,
                subtitle = "",
            )
        }
        ConnectionGrouping.Chain -> {
            val value = chains.trim()
            GroupDescriptor(key = "chain:$value", title = value, subtitle = "")
        }
    }
}

private fun Connection.appGroupDescriptor(appLabels: Map<String, String>): GroupDescriptor {
    val packageValue = packageName.trim()
    val processValue = process.trim()
    val label = appLabels[packageValue].orEmpty().trim()
    val key = when {
        packageValue.isNotEmpty() -> "package:$packageValue"
        uid != 0 -> "uid:$uid"
        processValue.isNotEmpty() -> "process:$processValue"
        else -> "app:unknown"
    }
    val title = label.ifEmpty {
        processValue.ifEmpty {
            packageValue.ifEmpty { uid.takeIf { it != 0 }?.let { "uid=$it" }.orEmpty() }
        }
    }
    val details = ArrayList<String>(3)
    if (processValue.isNotEmpty() && processValue != title) details += processValue
    if (packageValue.isNotEmpty() && packageValue != title) details += packageValue
    if (uid != 0) details += "uid=$uid"

    return GroupDescriptor(key = key, title = title, subtitle = details.joinToString(" · "))
}

private fun String.searchTerms(): List<String> {
    return trim().split(WHITESPACE).filter { it.isNotEmpty() }
}

private val WHITESPACE = Regex("\\s+")
