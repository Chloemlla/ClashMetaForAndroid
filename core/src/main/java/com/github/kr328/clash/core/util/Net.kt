package com.github.kr328.clash.core.util

import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Parse `host:port` / `[ipv6]:port` without allocating a [java.net.URL]
 * (hot path for every connection-owner UID query on API 29+).
 *
 * Numeric literals are parsed directly; anything else comes back as an unresolved address so a
 * per-connection hot path never blocks on name resolution.
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
    val literal = parseNumericLiteral(host)

    return if (literal != null) {
        InetSocketAddress(literal, port)
    } else {
        InetSocketAddress.createUnresolved(host, port)
    }
}

private fun parseNumericLiteral(host: String): InetAddress? {
    if (host.isEmpty()) return null

    if (host.indexOf(':') >= 0) {
        // A trailing "%zone" is not a DNS name either; strip it and parse only the literal part.
        return parseIpv6(host.substringBefore('%'))
    }

    return parseIpv4(host)
}

private fun parseIpv4(host: String): InetAddress? {
    if (host.startsWith("+") || host.startsWith("-")) return null

    val parts = host.split('.')
    if (parts.size != 4) return null

    val bytes = ByteArray(4)
    for (i in parts.indices) {
        val value = parts[i].toIntOrNull() ?: return null
        if (value !in 0..255) return null
        bytes[i] = value.toByte()
    }

    return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
}

private fun parseIpv6(literal: String): InetAddress? {
    if (literal.isEmpty()) return null

    val doubleColon = literal.indexOf("::")
    if (doubleColon >= 0 && literal.indexOf("::", doubleColon + 1) >= 0) return null

    val bytes = if (doubleColon >= 0) {
        val left = parseIpv6Groups(literal.substring(0, doubleColon))
        val right = parseIpv6Groups(literal.substring(doubleColon + 2))
        if (left == null || right == null) return null

        val missing = 16 - left.size - right.size
        if (missing < 0) return null

        left + ByteArray(missing) + right
    } else {
        parseIpv6Groups(literal) ?: return null
    }

    if (bytes.size != 16) return null

    return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
}

private fun parseIpv6Groups(text: String): ByteArray? {
    if (text.isEmpty()) return ByteArray(0)

    val parts = text.split(':')
    if (parts.any { it.isEmpty() }) return null

    val result = ArrayList<Byte>(16)

    for (part in parts) {
        if (part.indexOf('.') >= 0) {
            // Embedded IPv4 tail: "::ffff:192.168.1.1"
            val v4 = parseIpv4(part) ?: return null
            result.add(v4.getAddress()[0])
            result.add(v4.getAddress()[1])
            result.add(v4.getAddress()[2])
            result.add(v4.getAddress()[3])
            continue
        }

        if (part.length > 4) return null

        val value = part.toIntOrNull(16) ?: return null
        result.add((value ushr 8).toByte())
        result.add((value and 0xff).toByte())
    }

    return result.toByteArray()
}
