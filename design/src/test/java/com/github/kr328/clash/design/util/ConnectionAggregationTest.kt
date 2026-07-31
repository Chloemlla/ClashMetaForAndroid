package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.Connection
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionAggregationTest {
    @Test
    fun aggregateByApp_groupsPackageAndUsesResolvedLabel() {
        val connections = listOf(
            connection(id = "1", packageName = "com.example.app", uid = 10001, upload = 5, download = 7),
            connection(id = "2", packageName = "com.example.app", uid = 10001, upload = 11, download = 13),
        )

        val result = aggregateConnections(
            connections = connections,
            grouping = ConnectionGrouping.App,
            sort = ConnectionAggregateSort.Bytes,
            appLabels = mapOf("com.example.app" to "Example App"),
        )

        assertEquals(1, result.size)
        assertEquals("Example App", result.single().title)
        assertEquals(2, result.single().count)
        assertEquals(16L, result.single().upload)
        assertEquals(20L, result.single().download)
    }

    @Test
    fun aggregateSort_switchesBetweenBytesAndCount() {
        val connections = listOf(
            connection(id = "1", host = "large.example", download = 100),
            connection(id = "2", host = "many.example", download = 10),
            connection(id = "3", host = "many.example", download = 10),
        )

        val byBytes = aggregateConnections(
            connections,
            ConnectionGrouping.Host,
            ConnectionAggregateSort.Bytes,
        )
        val byCount = aggregateConnections(
            connections,
            ConnectionGrouping.Host,
            ConnectionAggregateSort.Count,
        )

        assertEquals("large.example", byBytes.first().title)
        assertEquals("many.example", byCount.first().title)
    }

    @Test
    fun aggregateByChain_keepsDirectConnectionsInOneFallbackGroup() {
        val result = aggregateConnections(
            connections = listOf(
                connection(id = "1", chains = ""),
                connection(id = "2", chains = ""),
                connection(id = "3", chains = "Proxy A"),
            ),
            grouping = ConnectionGrouping.Chain,
            sort = ConnectionAggregateSort.Count,
        )

        assertEquals(2, result.size)
        assertEquals("", result.first().title)
        assertEquals(2, result.first().count)
    }

    @Test
    fun aggregateByHost_groupsDnsNamesIgnoringCase() {
        val result = aggregateConnections(
            connections = listOf(
                connection(id = "1", host = "Example.COM"),
                connection(id = "2", host = "example.com"),
            ),
            grouping = ConnectionGrouping.Host,
            sort = ConnectionAggregateSort.Count,
        )

        assertEquals(1, result.size)
        assertEquals(2, result.single().count)
    }

    private fun connection(
        id: String,
        host: String = "",
        chains: String = "",
        upload: Long = 0L,
        download: Long = 0L,
        packageName: String = "",
        uid: Int = 0,
    ): Connection {
        return Connection(
            id = id,
            network = "tcp",
            type = "HTTPS",
            host = host,
            dstIp = "203.0.113.1",
            dstPort = "443",
            srcIp = "192.0.2.1",
            srcPort = "12345",
            chains = chains,
            upload = upload,
            download = download,
            packageName = packageName,
            uid = uid,
        )
    }
}
