package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyFilterTest {
    private val proxies = listOf(
        Proxy("hk-1", "Hong Kong 1", "ss", "Shadowsocks", 120, false),
        Proxy("jp-edge", "Tokyo Edge", "vmess", "VMess", 80, false),
        Proxy("auto", "Auto", "url-test", "URLTest", 0, true),
    )

    @Test
    fun filterByKeyword_matchesNameTitleSubtitleAndType() {
        assertEquals(listOf(proxies[0]), proxies.filterByKeyword("hong"))
        assertEquals(listOf(proxies[1]), proxies.filterByKeyword("TOKYO"))
        assertEquals(listOf(proxies[1]), proxies.filterByKeyword("vmess"))
        assertEquals(listOf(proxies[2]), proxies.filterByKeyword("auto"))
    }

    @Test
    fun filterByKeyword_returnsAllForBlankQuery() {
        assertEquals(proxies, proxies.filterByKeyword("  "))
    }

    @Test
    fun indexOfSelected_findsProxyByName() {
        assertEquals(1, proxies.indexOfSelected("jp-edge"))
        assertEquals(-1, proxies.indexOfSelected("missing"))
        assertEquals(-1, proxies.indexOfSelected(null))
    }
}
