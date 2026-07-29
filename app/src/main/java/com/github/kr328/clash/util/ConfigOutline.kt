package com.github.kr328.clash.util

/**
 * Best-effort, read-only structure scan for a Clash/Mihomo YAML profile.
 *
 * This is intentionally *not* a real YAML parser: it is a small line-based scanner that
 * counts direct list items under the top-level `proxies` / `proxy-groups` / `rules` keys.
 * It never throws — malformed or unexpected content degrades to [Counts.malformed] so the
 * UI can show a friendly message instead of crashing.
 */
object ConfigOutline {
    data class Counts(
        val proxies: Int = 0,
        val proxyGroups: Int = 0,
        val rules: Int = 0,
        val malformed: Boolean = false,
    )

    private const val KEY_PROXIES = "proxies"
    private const val KEY_PROXY_GROUPS = "proxy-groups"
    private const val KEY_RULES = "rules"

    fun count(yaml: String): Counts {
        return try {
            var proxies = 0
            var proxyGroups = 0
            var rules = 0

            var currentKey: String? = null
            var itemIndent: Int? = null

            for (rawLine in yaml.lineSequence()) {
                val line = stripComment(rawLine)

                if (line.isBlank()) continue

                val indent = line.takeWhile { it == ' ' }.length
                val trimmed = line.trim()

                if (indent == 0) {
                    val key = trimmed.removeSuffix(":").trim()

                    currentKey = when (key) {
                        KEY_PROXIES, KEY_PROXY_GROUPS, KEY_RULES -> key
                        else -> null
                    }
                    itemIndent = null

                    continue
                }

                if (currentKey == null) continue

                val isListItem = trimmed == "-" || trimmed.startsWith("- ")

                if (!isListItem) continue

                // Only count items at the section's own indent level; deeper-indented
                // list items (e.g. a proxy-group's nested `proxies:` member list) belong
                // to a child field, not a new top-level entry.
                if (itemIndent == null) itemIndent = indent
                if (indent != itemIndent) continue

                when (currentKey) {
                    KEY_PROXIES -> proxies++
                    KEY_PROXY_GROUPS -> proxyGroups++
                    KEY_RULES -> rules++
                }
            }

            Counts(proxies = proxies, proxyGroups = proxyGroups, rules = rules)
        } catch (e: Exception) {
            Counts(malformed = true)
        }
    }

    // Best-effort: does not understand quoted strings that contain '#', which is
    // acceptable for a read-only outline scan.
    private fun stripComment(line: String): String {
        val index = line.indexOf('#')

        return if (index >= 0) line.substring(0, index) else line
    }
}
