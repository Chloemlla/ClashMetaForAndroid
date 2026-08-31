package com.github.kr328.clash.common.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun CoroutineScope.ticker(period: Long): Channel<Long> {
    val channel = Channel<Long>(Channel.RENDEZVOUS)

    launch {
        try {
            while (isActive) {
                channel.send(System.currentTimeMillis())

                delay(period)
            }
        } catch (e: CancellationException) {
            // B-131: never swallow cancellation — re-throw so the parent scope observes the
            // coroutine's cancellation instead of treating it as an ordinary failure.
            throw e
        } catch (e: Exception) {
            channel.close(e)
        } finally {
            // B-131: release consumers (their `for (x in ticker)` / `ticker.onReceive` in select)
            // as soon as the producer ends, whether by cancellation, failure, or normal exit.
            channel.close()
        }
    }

    return channel
}
