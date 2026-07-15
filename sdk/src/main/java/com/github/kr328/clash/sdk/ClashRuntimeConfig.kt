package com.github.kr328.clash.sdk

import android.content.ComponentName

/**
 * Options applied during [ClashRuntime.install].
 *
 * @property mainActivity notification / VPN configure content intent target
 * @property propertiesActivity profile properties deep-link target (optional)
 * @property enableVpnByDefault when true, [ClashRuntime.start] prefers TunService after prepare
 * @property bindOnVisible when true, bind RemoteService when [ClashRuntime.bind] is called;
 *   hosts typically bind while UI is visible and unbind when backgrounded
 */
data class ClashRuntimeConfig(
    val mainActivity: ComponentName? = null,
    val propertiesActivity: ComponentName? = null,
    val enableVpnByDefault: Boolean = true,
    val bindOnVisible: Boolean = true,
)