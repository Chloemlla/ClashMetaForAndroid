package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable

/**
 * HTTP capture event pushed from the Go bridge layer.
 * Parsed from [HTTP]-prefixed mihomo log messages.
 * Captures only plain HTTP (non-HTTPS) request/response details.
 */
@Serializable
data class HttpRecord(
    val type: String,
    val method: String = "",
    val url: String = "",
    val host: String = "",
    val path: String = "",
    val statusCode: Int = 0,
    val body: String = "",
    val headers: String = "",
    val connection: String = "",
    val timestamp: Long = 0,
)