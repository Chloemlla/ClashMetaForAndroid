package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.LogMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class LogMessageFilterTest {
    @Test
    fun blankQuery_matchesEveryMessage() {
        assertTrue(message("anything").matchesLogQuery("  "))
    }

    @Test
    fun query_matchesMessageAndLevelIgnoringCase() {
        assertTrue(message("DNS cache hit").matchesLogQuery("dns"))
        assertTrue(
            message("rule provider refreshed", LogMessage.Level.Warning)
                .matchesLogQuery("warning"),
        )
    }

    @Test
    fun multipleTerms_allMustMatch() {
        val message = message("rule match: example.com")

        assertTrue(message.matchesLogQuery("RULE example.com"))
        assertFalse(message.matchesLogQuery("rule missing.example"))
    }

    private fun message(
        text: String,
        level: LogMessage.Level = LogMessage.Level.Info,
    ): LogMessage {
        return LogMessage(level = level, message = text, time = Date(0L))
    }
}
