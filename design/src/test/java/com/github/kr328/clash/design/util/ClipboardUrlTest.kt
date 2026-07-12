package com.github.kr328.clash.design.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardUrlTest {
    @Test
    fun extract_acceptsPlainHttpUrl() {
        assertEquals(
            "https://example.com/sub.yaml",
            ClipboardUrl.extract("https://example.com/sub.yaml")
        )
        assertEquals(
            "http://cdn.example.com/cfg",
            ClipboardUrl.extract("  http://cdn.example.com/cfg  ")
        )
    }

    @Test
    fun extract_acceptsClashInstallScheme() {
        val encoded = java.net.URLEncoder.encode("https://example.com/a.yaml", "UTF-8")
        assertEquals(
            "https://example.com/a.yaml",
            ClipboardUrl.extract("clash://install-config?url=$encoded")
        )
        assertEquals(
            "https://example.com/a.yaml",
            ClipboardUrl.extract("clashmeta://install-config?name=demo&url=$encoded&extra=1")
        )
    }

    @Test
    fun extract_findsUrlInsideText() {
        assertEquals(
            "https://example.com/x",
            ClipboardUrl.extract("订阅: https://example.com/x 请导入")
        )
    }

    @Test
    fun extract_rejectsInvalidInput() {
        assertNull(ClipboardUrl.extract(null))
        assertNull(ClipboardUrl.extract(""))
        assertNull(ClipboardUrl.extract("not a url"))
        assertNull(ClipboardUrl.extract("ftp://example.com/file"))
    }
}
