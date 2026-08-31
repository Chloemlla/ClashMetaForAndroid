package com.github.kr328.clash.log

import com.github.kr328.clash.core.model.LogMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class LogcatParserTest {
    @Test
    fun validLine_preservesMessageColons() {
        val parsed = parseLogLine("123:Info:host: port", Date(0))

        assertEquals(LogMessage.Level.Info, parsed.message?.level)
        assertEquals("host: port", parsed.message?.message)
        assertEquals(Date(123), parsed.lastTime)
    }

    @Test
    fun malformedLine_isRetainedAsWarning() {
        val parsed = parseLogLine("broken record", Date(99))

        assertEquals(LogMessage.Level.Warning, parsed.message?.level)
        assertEquals("broken record", parsed.message?.message)
        assertEquals(Date(99), parsed.message?.time)
    }

    @Test
    fun commentsAreIgnored() {
        assertNull(parseLogLine("# capture", Date(1)).message)
    }

    @Test
    fun multiLineMessage_survivesWriteThenParse() {
        val original = "panic: bad config\n\tat proxy.go:42\r\nliteral backslash \\n stays"

        val parsed = parseLogLine("7:Error:${LogcatWriter.escape(original)}", Date(0))

        assertEquals(LogMessage.Level.Error, parsed.message?.level)
        assertEquals(original, parsed.message?.message)
    }

    @Test
    fun escapedRecord_staysOnOneLine() {
        assertEquals(
            "a\\nb",
            LogcatWriter.escape("a\nb"),
        )
    }
}
