package com.github.kr328.clash.service.store

import com.github.kr328.clash.common.util.packageName
import com.github.kr328.clash.service.model.WidgetState

/**
 * In-memory latest [WidgetState] for same-process consumers (and StatusProvider bridge).
 *
 * [update] is a no-op when [WidgetState.sameAs] the current snapshot.
 * Publishers may send [ACTION_WIDGET_STATE_CHANGED] only after [update] returns true.
 */
object WidgetStateStore {
    /** Same-app self-broadcast action (package-prefixed like other Intents). */
    val ACTION_WIDGET_STATE_CHANGED: String
        get() = "$packageName.intent.action.WIDGET_STATE_CHANGED"

    @Volatile
    private var latest: WidgetState? = null

    /**
     * Replace the snapshot when content changed.
     * @return true if stored value changed
     */
    @Synchronized
    fun update(state: WidgetState): Boolean {
        if (state.sameAs(latest)) {
            return false
        }
        latest = state
        return true
    }

    fun current(): WidgetState? = latest

    @Synchronized
    fun clear() {
        latest = null
    }
}
