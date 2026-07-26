package com.github.kr328.clash.design.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class ProfileShareUriTest {
    @Test
    fun isShareableHttpSource_acceptsHttpAndHttps() {
        assertTrue(ProfileShareUri.isShareableHttpSource("https://example.com/sub.yaml"))
        assertTrue(ProfileShareUri.isShareableHttpSource("  http://cdn.example.com/a  "))
        assertFalse(ProfileShareUri.isShareableHttpSource(""))
        assertFalse(ProfileShareUri.isShareableHttpSource(null))
        assertFalse(ProfileShareUri.isShareableHttpSource("ftp://example.com/x"))
        assertFalse(ProfileShareUri.isShareableHttpSource("content://local/file"))
    }

    @Test
    fun installConfigUri_buildsClashMetaSchemeRoundTrip() {
        val source = "https://example.com/sub.yaml?token=a+b"
        val uri = ProfileShareUri.installConfigUri(source)
        assertTrue(uri!!.startsWith("clashmeta://install-config?url="))

        val encoded = uri.removePrefix("clashmeta://install-config?url=")
        val decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        assertEquals(source, decoded)

        // Import path must recover the same URL.
        assertEquals(source, ClipboardUrl.extract(uri))
    }

    @Test
    fun installConfigUri_rejectsNonHttp() {
        assertNull(ProfileShareUri.installConfigUri("file:///tmp/x.yaml"))
        assertNull(ProfileShareUri.qrPayload(""))
        assertNull(ProfileShareUri.qrPayload(null))
    }
}
