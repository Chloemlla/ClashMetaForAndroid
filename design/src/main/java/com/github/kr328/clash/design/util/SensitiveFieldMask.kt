package com.github.kr328.clash.design.util

/**
 * Display helpers for sensitive Properties fields (subscription URL, age secret key).
 * Secrets stay in the model; only the rendered text is masked.
 */
object SensitiveFieldMask {
    private const val BULLET = '•'
    private const val MASK_LENGTH = 12

    fun isBlank(value: CharSequence?): Boolean = value.isNullOrEmpty()

    /** Full bullet mask for secret keys (or empty when blank). */
    fun maskSecret(value: CharSequence?): String {
        if (value.isNullOrEmpty()) return ""
        return BULLET.toString().repeat(MASK_LENGTH.coerceAtMost(value.length.coerceAtLeast(8)))
    }

    /**
     * Mask a URL-like source while keeping a short host hint when possible.
     * Example: `https://example.com/path?token=…` → `https://example.com/••••••••`
     */
    fun maskUrl(value: CharSequence?): String {
        if (value.isNullOrEmpty()) return ""
        val text = value.toString()
        val schemeSep = text.indexOf("://")
        if (schemeSep < 0) {
            return maskSecret(text)
        }
        val hostStart = schemeSep + 3
        val hostEnd = text.indexOf('/', hostStart).let { if (it < 0) text.length else it }
        if (hostEnd <= hostStart) {
            return maskSecret(text)
        }
        val prefix = text.substring(0, hostEnd)
        return prefix + "/" + BULLET.toString().repeat(8)
    }

    fun display(
        value: CharSequence?,
        revealed: Boolean,
        urlStyle: Boolean,
    ): String {
        if (value.isNullOrEmpty()) return ""
        if (revealed) return value.toString()
        return if (urlStyle) maskUrl(value) else maskSecret(value)
    }
}
