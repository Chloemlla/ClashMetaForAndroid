package com.github.kr328.clash.service

import android.content.Intent
import android.os.IBinder
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.remote.wrap
import com.github.kr328.clash.service.util.cancelAndJoinBlocking

class RemoteService : BaseService(), IRemoteService {
    private val binder = this.wrap()

    private var clash: ClashManager? = null
    private var profile: ProfileManager? = null
    private var clashBinder: IClashManager? = null
    private var profileBinder: IProfileManager? = null

    override fun onCreate() {
        super.onCreate()

        clash = ClashManager(this)
        profile = ProfileManager(this)
        clashBinder = clash?.wrap() as IClashManager?
        profileBinder = profile?.wrap() as IProfileManager?
    }

    override fun onDestroy() {
        // Cancel managers first so their work is not restarted during service teardown. Both
        // joins are time-bounded; releasing their state while children still run would let a
        // restart observe a half-torn-down runtime.
        clash?.cancelAndJoinBlocking()
        profile?.cancelAndJoinBlocking()

        // After their scopes are cancelled the managers are half-alive: ClashManager's observer
        // relays are dead while ProfileManager's suspend methods still write the database. Null the
        // binders so any IPC that slips in during the unbind window fails loudly instead of silently
        // splitting "some calls work, others no-op" across the same interface (B-184).
        clash = null
        profile = null
        clashBinder = null
        profileBinder = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun clash(): IClashManager {
        return clashBinder ?: throw IllegalStateException("RemoteService is destroyed")
    }

    override fun profile(): IProfileManager {
        return profileBinder ?: throw IllegalStateException("RemoteService is destroyed")
    }
}