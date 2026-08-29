package com.github.kr328.clash.design.util

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds shareable profile payloads for QR export / deep-link parity.
 *
 * The payload is a `clashmeta://install-config?url=<encoded>[&name=<encoded>]`
 * URI. [installConfigUri] keeps scanners and clipboard import able to recover
 * the original http(s) subscription URL via [ClipboardUrl.extract], while the
 * optional profile [name] is preserved for importers that understand it.
 */
object ProfileShareUri {
    private const val INSTALL_CONFIG_PREFIX = "clashmeta://install-config?url="
    private val installConfigRegex = Regex(
        """(?i)^clash(?:meta)?://install-config\?(?:.*&)?url=([^&]+)(?:&name=([^&]+))?(?:&.*)?$"""
    )

    data class InstallConfig(val url: String, val name: String?)

    fun isShareableHttpSource(source: String?): Boolean {
        val value = source?.trim().orEmpty()
        return ValidatorHttpUrl(value)
    }

    /**
     * Returns `clashmeta://install-config?url=<encoded>[&name=<encoded>]` for a
     * valid http(s) subscription [source], or null when the source is not
     * shareable.
     */
    fun installConfigUri(source: String?, name: String? = null): String? {
        val url = source?.trim().orEmpty()
        if (!isShareableHttpSource(url)) return null

        val encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8.name())
        val encodedName = name?.trim()?.takeIf { it.isNotEmpty() }?.let {
            URLEncoder.encode(it, StandardCharsets.UTF_8.name())
        } ?: return INSTALL_CONFIG_PREFIX + encodedUrl

        return "$INSTALL_CONFIG_PREFIX$encodedUrl&name=$encodedName"
    }

    /**
     * Parses an install-config URI back into its subscription URL and the
     * optional profile name. Returns null when [text] is not an install-config
     * URI with a shareable subscription URL.
     */
    fun parseInstallConfig(text: String?): InstallConfig? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null

        val match = installConfigRegex.matchEntire(value) ?: return null
        val url = decode(match.groupValues[1])?.takeIf { ValidatorHttpUrl(it) } ?: return null
        val name = match.groupValues.getOrNull(2)
            ?.takeIf { it.isNotEmpty() }
            ?.let { decode(it) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return InstallConfig(url, name)
    }

    /**
     * Payload written into the QR code. Uses install-config URI when possible
     * so import paths that understand the scheme stay consistent.
     */
    fun qrPayload(source: String?, name: String? = null): String? =
        installConfigUri(source, name)

    private fun decode(value: String): String? =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull()
}
