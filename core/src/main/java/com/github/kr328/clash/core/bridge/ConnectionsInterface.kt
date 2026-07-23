package com.github.kr328.clash.core.bridge

import androidx.annotation.Keep

@Keep
interface ConnectionsInterface {
    /**
     * Deliver one connections snapshot (JSON payload).
     * @return false when the subscriber should stop (channel closed / disposed).
     */
    fun received(jsonPayload: String): Boolean
}
