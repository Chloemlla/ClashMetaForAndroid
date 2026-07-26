package com.github.kr328.clash.design.util

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds shareable profile payloads for QR export / deep-link parity.
 *
 * Prefer [installConfigUri] so scanners and clipboard import can recover the
 * original http(s) subscription URL via [ClipboardUrl.extract].
 */
object ProfileShareUri {
    private const val INSTALL_CONFIG_PREFIX = "clashmeta://install-config?url="

    fun isShareableHttpSource(source: String?): Boolean {
        val value = source?.trim().orEmpty()
        return ValidatorHttpUrl(value)
    }

    /**
     * Returns `clashmeta://install-config?url=<encoded>` for a valid http(s)
     * subscription [source], or null when the source is not shareable.
     */
    fun installConfigUri(source: String?): String? {
        val url = source?.trim().orEmpty()
        if (!isShareableHttpSource(url)) return null

        val encoded = URLEncoder.encode(url, StandardCharsets.UTF_8.name())
        return INSTALL_CONFIG_PREFIX + encoded
    }

    /**
     * Payload written into the QR code. Uses install-config URI when possible
     * so import paths that understand the scheme stay consistent.
     */
    fun qrPayload(source: String?): String? = installConfigUri(source)
}
