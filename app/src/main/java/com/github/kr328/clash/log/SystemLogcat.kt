package com.github.kr328.clash.log

import java.util.concurrent.TimeUnit

object SystemLogcat {
    private const val MAX_LINES = 512
    private const val TIMEOUT_SECONDS = 5L

    // -t bounds the dump at the source: dumpCrash() runs on an already-stressed process, so the
    // whole buffer must never be pulled into the heap.
    private val command = arrayOf(
        "logcat",
        "-d",
        "-t",
        MAX_LINES.toString(),
        "-s",
        "Go",
        "DEBUG",
        "AndroidRuntime",
        "ClashMetaForAndroid",
        "LwIP",
    )

    fun dumpCrash(): String {
        var process: Process? = null

        return try {
            // Merged streams: an unread stderr pipe fills up and parks logcat in write() while we
            // wait for it to exit.
            process = ProcessBuilder(*command).redirectErrorStream(true).start()

            val result = process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence()
                    .filterNot { it.startsWith("------") }
                    .joinToString("\n")
            }

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }

            result.trim()
        } catch (e: Exception) {
            process?.destroyForcibly()
            ""
        }
    }
}
