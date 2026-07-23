package com.github.kr328.clash.service.remote

import com.github.kr328.clash.core.model.ConnectionSnapshot
import com.github.kr328.kaidl.BinderInterface

@BinderInterface
interface IConnectionsObserver {
    fun newSnapshot(snapshot: ConnectionSnapshot)
}
