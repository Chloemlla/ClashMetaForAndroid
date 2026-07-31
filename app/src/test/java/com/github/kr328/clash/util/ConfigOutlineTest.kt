package com.github.kr328.clash.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigOutlineTest {
    @Test
    fun countsOnlyDirectItemsInSupportedSections() {
        val yaml = """
            proxies:
              - name: first
                type: ss
              - name: second
                type: vmess
            proxy-groups:
              - name: automatic
                type: select
                proxies:
                  - first
                  - second
            rules:
              - DOMAIN-SUFFIX,example.com,automatic
              - MATCH,automatic
        """.trimIndent()

        assertEquals(
            ConfigOutline.Counts(proxies = 2, proxyGroups = 1, rules = 2),
            ConfigOutline.count(yaml),
        )
    }

    @Test
    fun malformedSectionFailsSoftly() {
        val counts = ConfigOutline.count("proxies:\n\t- name: invalid indentation")

        assertTrue(counts.malformed)
        assertEquals(0, counts.proxies)
    }

    @Test
    fun countsValidIndentlessSectionSequences() {
        val yaml = """
            proxies:
            - name: first
              type: ss
            proxy-groups:
            - name: automatic
              type: select
            rules:
            - MATCH,automatic
        """.trimIndent()

        assertEquals(
            ConfigOutline.Counts(proxies = 1, proxyGroups = 1, rules = 1),
            ConfigOutline.count(yaml),
        )
    }
}
