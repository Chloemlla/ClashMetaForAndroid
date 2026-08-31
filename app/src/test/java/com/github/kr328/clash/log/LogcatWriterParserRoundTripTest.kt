package com.github.kr328.clash.log

import com.github.kr328.clash.core.model.LogMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

/**
 * B-99 / B-19: a written record must survive the escape → parse round-trip intact, including
 * multi-line payloads, colons, and literal backslashes. The on-disk format is:
 * `timestamp:LEVEL:<escaped message>\n`.
 */
class LogcatWriterParserRoundTripTest {
    private fun roundTrip(message: String, level: LogMessage.Level = LogMessage.Level.Info): String {
        val line = "${0L}:${level.name}:${LogcatWriter.escape(message)}"
        val parsed = requireNotNull(parseLogLine(line, Date(0)).message)
        assertEquals(level, parsed.level)
        return parsed.message
    }

    @Test
    fun plainMessageRoundTrips() {
        assertEquals("GET /index.html HTTP/1.1", roundTrip("GET /index.html HTTP/1.1"))
    }

    @Test
    fun messageWithColonsRoundTrips() {
        assertEquals("host: port 1:2:3", roundTrip("host: port 1:2:3"))
    }

    @Test
    fun multiLineMessageRoundTrips() {
        val original = "panic: bad config\n\tat proxy.go:42\n\tat main.go:7"
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun literalBackslashAndCRRoundTrip() {
        val original = "path C:\\config\\rules\\a.yaml\r\nnext line"
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun escapedEscapeSequenceDoesNotInventRecords() {
        // A message whose text is literally "\\n" (backslash n) must not decode into a newline.
        assertEquals("a\\nb", roundTrip("a\\nb"))
        assertEquals("a\nb", roundTrip("a\nb"))
    }

    @Test
    fun levelNamesRoundTripForEveryLevel() {
        for (level in LogMessage.Level.values()) {
            assertEquals("msg-$level", roundTrip("msg-$level", level))
        }
    }
}
