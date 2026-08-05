package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable

/**
 * DNS capture event pushed from the Go bridge layer.
 * Parsed from [DNS]-prefixed mihomo log messages (DEBUG level).
 */
@Serializable
data class DnsRecord(
    val type: String,
    val domain: String = "",
    val qtype: String = "",
    val server: String = "",
    val result: String = "",
    val expireAt: String = "",
    val timestamp: Long = 0,
)