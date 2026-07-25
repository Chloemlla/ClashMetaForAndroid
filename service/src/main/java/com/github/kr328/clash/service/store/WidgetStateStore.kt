package com.github.kr328.clash.service.store

import com.github.kr328.clash.service.model.WidgetState

/**
 * In-memory latest [WidgetState] for same-process consumers.
 *
 * [update] is a no-op when [WidgetState.sameAs] the current snapshot.
 * Reserved self-broadcast action constant is for M2 AppWidget observers only;
 * this milestone does not register receivers.
 */
object WidgetStateStore {
    /** Reserved for M2 non-exported widget refresh observers. */
    const val ACTION_WIDGET_STATE_CHANGED =
        "com.github.kr328.clash.intent.action.WIDGET_STATE_CHANGED"

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
