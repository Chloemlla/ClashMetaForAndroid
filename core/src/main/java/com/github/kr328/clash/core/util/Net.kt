package com.github.kr328.clash.core.util

import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Parse `host:port` / `[ipv6]:port` without allocating a [java.net.URL]
 * (hot path for every connection-owner UID query on API 29+).
 */
fun parseInetSocketAddress(address: String): InetSocketAddress {
    val host: String
    val portString: String

    if (address.startsWith("[")) {
        val close = address.indexOf(']')
        require(close > 0 && close + 1 < address.length && address[close + 1] == ':') {
            "invalid socket address: $address"
        }
        host = address.substring(1, close)
        portString = address.substring(close + 2)
    } else {
        val sep = address.lastIndexOf(':')
        require(sep > 0 && sep < address.length - 1) { "invalid socket address: $address" }
        host = address.substring(0, sep)
        portString = address.substring(sep + 1)
    }

    val port = portString.toInt()
    return InetSocketAddress(InetAddress.getByName(host), port)
}