package com.github.kr328.clash.service.remote

import com.github.kr328.clash.core.model.AdblockHit
import com.github.kr328.kaidl.BinderInterface

@BinderInterface
interface IAdblockObserver {
    fun onHit(hit: AdblockHit)
}
