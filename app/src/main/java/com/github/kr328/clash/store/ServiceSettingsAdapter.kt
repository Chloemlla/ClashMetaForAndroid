package com.github.kr328.clash.store

import com.github.kr328.clash.design.model.AccessControlMode
import com.github.kr328.clash.design.model.ServiceSettings
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.model.AccessControlMode as ServiceAccessControlMode

class ServiceSettingsAdapter(
    private val store: ServiceStore,
) : ServiceSettings {
    override var bypassPrivateNetwork: Boolean
        get() = store.bypassPrivateNetwork
        set(value) {
            store.bypassPrivateNetwork = value
        }

    override var accessControlMode: AccessControlMode
        get() = store.accessControlMode.toDesignMode()
        set(value) {
            store.accessControlMode = value.toServiceMode()
        }

    override var dnsHijacking: Boolean
        get() = store.dnsHijacking
        set(value) {
            store.dnsHijacking = value
        }

    override var systemProxy: Boolean
        get() = store.systemProxy
        set(value) {
            store.systemProxy = value
        }

    override var allowBypass: Boolean
        get() = store.allowBypass
        set(value) {
            store.allowBypass = value
        }

    override var allowIpv6: Boolean
        get() = store.allowIpv6
        set(value) {
            store.allowIpv6 = value
        }

    override var tunStackMode: String
        get() = store.tunStackMode
        set(value) {
            store.tunStackMode = value
        }

    override var dynamicNotification: Boolean
        get() = store.dynamicNotification
        set(value) {
            store.dynamicNotification = value
        }

    override var localSubscriptionTraffic: Boolean
        get() = store.localSubscriptionTraffic
        set(value) {
            store.localSubscriptionTraffic = value
        }

    private fun ServiceAccessControlMode.toDesignMode(): AccessControlMode = when (this) {
        ServiceAccessControlMode.AcceptAll -> AccessControlMode.AcceptAll
        ServiceAccessControlMode.AcceptSelected -> AccessControlMode.AcceptSelected
        ServiceAccessControlMode.DenySelected -> AccessControlMode.DenySelected
    }

    private fun AccessControlMode.toServiceMode(): ServiceAccessControlMode = when (this) {
        AccessControlMode.AcceptAll -> ServiceAccessControlMode.AcceptAll
        AccessControlMode.AcceptSelected -> ServiceAccessControlMode.AcceptSelected
        AccessControlMode.DenySelected -> ServiceAccessControlMode.DenySelected
    }
}
