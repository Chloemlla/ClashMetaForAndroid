package com.github.kr328.clash.sdk

import java.util.UUID

/**
 * Runtime lifecycle and profile events mirrored from self-broadcasts.
 * Only delivered while the host has registered receivers via [ClashRuntime].
 */
sealed class ClashRuntimeEvent {
    data object ServiceRecreated : ClashRuntimeEvent()
    data object Started : ClashRuntimeEvent()
    data class Stopped(val reason: String?) : ClashRuntimeEvent()
    data object ProfileChanged : ClashRuntimeEvent()
    data object ProfileLoaded : ClashRuntimeEvent()
    data class ProfileUpdateCompleted(val uuid: UUID?) : ClashRuntimeEvent()
    data class ProfileUpdateFailed(val uuid: UUID?, val reason: String?) : ClashRuntimeEvent()
}