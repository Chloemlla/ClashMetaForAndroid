package com.github.kr328.clash.core.bridge

import androidx.annotation.Keep

@Keep
interface HttpCaptureInterface {
    /**
     * Deliver one HTTP capture event (JSON payload).
     * @return false when the subscriber should stop (channel closed / disposed).
     */
    fun received(jsonPayload: String): Boolean
}