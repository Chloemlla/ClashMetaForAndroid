package com.github.kr328.clash.design.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun qrPayloadWithName_roundTripsUrlAndName() {
        val source = "https://example.com/sub.yaml?token=a+b"
        val name = "我的 节点/配置"

        val payload = ProfileShareUri.qrPayload(source, name)
        assertNotNull(payload)
        assertTrue(payload!!.startsWith("clashmeta://install-config?url="))

        val parsed = ProfileShareUri.parseInstallConfig(payload)
        assertNotNull(parsed)
        assertEquals(source, parsed?.url)
        assertEquals(name, parsed?.name)
    }

    @Test
    fun qrPayloadWithoutName_parsesNameAsNull() {
        val source = "https://example.com/sub.yaml"

        val payload = ProfileShareUri.qrPayload(source)
        assertNotNull(payload)

        val parsed = ProfileShareUri.parseInstallConfig(payload)
        assertNotNull(parsed)
        assertEquals(source, parsed?.url)
        assertNull(parsed?.name)
    }

    @Test
    fun qrPayloadBlankName_omitsNameParam() {
        val payload = ProfileShareUri.qrPayload("https://example.com/sub.yaml", "  ")
        assertNotNull(payload)
        assertEquals(
            "clashmeta://install-config?url=" + java.net.URLEncoder.encode(
                "https://example.com/sub.yaml", "UTF-8"
            ),
            payload
        )
    }

    @Test
    fun parseInstallConfig_rejectsInvalidInput() {
        assertNull(ProfileShareUri.parseInstallConfig(null))
        assertNull(ProfileShareUri.parseInstallConfig(""))
        assertNull(ProfileShareUri.parseInstallConfig("https://example.com/sub.yaml"))
        assertNull(ProfileShareUri.parseInstallConfig("clashmeta://install-config?url=not-a-url"))
        assertNull(ProfileShareUri.parseInstallConfig("ftp://example.com/file"))
    }
}
