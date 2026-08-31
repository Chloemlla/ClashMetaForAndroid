package com.github.kr328.clash.sdk

import java.util.UUID

/**
 * Runtime lifecycle and profile events mirrored from self-broadcasts.
 * Only delivered while the host has registered receivers via [ClashRuntime].
 *
 * **Compatibility contract:** this sealed hierarchy is expected to grow. Consumers MUST
 * write an `else` branch (or handle [Unknown]) when `when`-ing over events, so a future
 * SDK release adding a new member neither breaks host compilation nor throws
 * `NoWhenBranchMatchedException` on already-compiled hosts.
 */
sealed class ClashRuntimeEvent {
    data object ServiceRecreated : ClashRuntimeEvent()
    data object Started : ClashRuntimeEvent()
    data class Stopped(val reason: String?) : ClashRuntimeEvent()
    data object ProfileChanged : ClashRuntimeEvent()
    data object ProfileLoaded : ClashRuntimeEvent()
    data class ProfileUpdateCompleted(val uuid: UUID?) : ClashRuntimeEvent()
    data class ProfileUpdateFailed(val uuid: UUID?, val reason: String?) : ClashRuntimeEvent()

    /** Fallback for events introduced by a newer SDK version than the host was built against. */
    data class Unknown(val name: String) : ClashRuntimeEvent()
}