package com.github.kr328.clash.design.util

object ClipboardUrl {
    private val clashScheme = Regex(
        """(?i)^clash(?:meta)?://install-config\?(?:.*&)?url=([^&]+)(?:&.*)?$"""
    )
    private val bareHttpUrl = Regex(
        """(?i)\bhttps?://[^\s<>"'`]+"""
    )

    fun extract(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        clashScheme.matchEntire(text)?.groupValues?.getOrNull(1)?.let { encoded ->
            return decodeCandidate(encoded)
        }

        if (ValidatorHttpUrl(text)) {
            return sanitizeHttpUrl(text)
        }

        bareHttpUrl.find(text)?.value?.let { match ->
            return sanitizeHttpUrl(match)
        }

        return null
    }

    private fun decodeCandidate(value: String): String? {
        val decoded = runCatching {
            java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
        }.getOrDefault(value).trim()

        return if (ValidatorHttpUrl(decoded)) sanitizeHttpUrl(decoded) else null
    }

    private fun sanitizeHttpUrl(value: String): String {
        return value.trim().trimEnd(',', '.', ';', ')', ']', '}', '"', '\'')
    }
}
