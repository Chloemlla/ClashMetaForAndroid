package com.github.kr328.clash.design.model

enum class AccessControlMode {
    AcceptAll,
    AcceptSelected,
    DenySelected,
}

interface ServiceSettings {
    var bypassPrivateNetwork: Boolean
    var accessControlMode: AccessControlMode
    var dnsHijacking: Boolean
    var systemProxy: Boolean
    var allowBypass: Boolean
    var allowIpv6: Boolean
    var tunStackMode: String
    var dynamicNotification: Boolean
    var localSubscriptionTraffic: Boolean
}
