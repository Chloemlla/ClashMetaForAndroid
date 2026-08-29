package com.github.kr328.clash.design.model

/** How the device owner has answered a partner app's request to read Clash status. */
enum class PartnerAuthorization {
    /** Explicitly allowed: the app may read the detailed status. */
    Allowed,

    /** Explicitly refused: the app is denied even if its certificate is pinned. */
    Denied,

    /** Asked but not answered yet. */
    Pending,

    /** Never asked. */
    Undecided,
}

/**
 * One row of the partner app list.
 *
 * [tunneled] and [authorization] are independent: an app can have its traffic carried by the tunnel
 * without being allowed to read status, and vice versa.
 */
data class PartnerAppInfo(
    val packageName: String,
    val label: String,
    val certificateSha256: String?,
    val signerVerified: Boolean,
    val authorization: PartnerAuthorization,
    val tunneled: Boolean,
)
