package com.github.kr328.clash.sdk

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.DeadObjectException
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.sdk.internal.EventHub
import com.github.kr328.clash.sdk.internal.RemoteSession
import com.github.kr328.clash.service.ClashService
import com.github.kr328.clash.service.TunService
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.util.sendBroadcastSelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * Embedded Runtime / Service SDK facade (Direction B).
 *
 * Wraps the existing `:service` process model (RemoteService + TunService/ClashService)
 * so host applications can manage profiles and VPN without the stock CMFA UI.
 *
 * **Security:** in-app / same-signature only. Does not re-open third-party remote control.
 *
 * Typical host flow:
 * 1. [install] in `Application.onCreate` (main process)
 * 2. [configureHostUi] or pass components via [ClashRuntimeConfig]
 * 3. Extract geo assets into `filesDir/clash` (see docs)
 * 4. [bind] while UI is active
 * 5. [importUrlProfile] → [setActive] → [start] (handle VPN prepare Intent)
 * 6. [queryProxyGroups] / [selectProxy] / observe [events]
 * 7. [stop] / [unbind]
 */
object ClashRuntime {
    @Volatile
    private var app: Application? = null

    @Volatile
    private var config: ClashRuntimeConfig = ClashRuntimeConfig()

    @Volatile
    private var session: RemoteSession? = null

    @Volatile
    private var eventHub: EventHub? = null

    @Volatile
    private var enableVpn: Boolean = true

    /** Runtime lifecycle events; empty until [install]. */
    val events: SharedFlow<ClashRuntimeEvent>
        get() = requireEventHub().events

    /** Best-effort running flag from last received start/stop broadcasts. */
    val isRunning: Boolean
        get() = eventHub?.clashRunning == true

    /**
     * Initialize Global, host component overrides, and binder session plumbing.
     * Must run on the **main process** before any other SDK call.
     */
    @JvmStatic
    @JvmOverloads
    fun install(
        application: Application,
        config: ClashRuntimeConfig = ClashRuntimeConfig(),
        onServiceCrashed: () -> Unit = {
            Log.w("ClashRuntime: RemoteService crashed repeatedly")
        },
    ) {
        synchronized(this) {
            Global.init(application)
            this.app = application
            this.config = config
            this.enableVpn = config.enableVpnByDefault

            if (config.mainActivity != null || config.propertiesActivity != null) {
                Components.configure(
                    mainActivity = config.mainActivity,
                    propertiesActivity = config.propertiesActivity,
                )
            }

            if (session == null) {
                session = RemoteSession(application, onServiceCrashed)
            }
            if (eventHub == null) {
                eventHub = EventHub(application)
            }
        }
    }

    /** Update notification / VPN configure activity targets after install. */
    @JvmStatic
    fun configureHostUi(
        mainActivity: android.content.ComponentName? = null,
        propertiesActivity: android.content.ComponentName? = null,
    ) {
        Components.configure(mainActivity, propertiesActivity)
    }

    /** Prefer TunService (VPN) vs ClashService (HTTP only) for subsequent [start] calls. */
    @JvmStatic
    fun setVpnEnabled(enabled: Boolean) {
        enableVpn = enabled
    }

    /** Bind [com.github.kr328.clash.service.RemoteService] and register event receivers. */
    @JvmStatic
    fun bind() {
        requireInstalled()
        requireEventHub().register()
        requireSession().bind()
    }

    /** Unbind remote service and drop event receivers. */
    @JvmStatic
    fun unbind() {
        eventHub?.unregister()
        session?.unbind()
    }

    /**
     * Start Clash runtime.
     *
     * @return non-null [Intent] from [VpnService.prepare] when the user must grant VPN;
     *   launch it with `startActivityForResult` / Activity Result API, then call [start] again.
     *   Returns null when start was issued (VPN already prepared or non-VPN mode).
     */
    @JvmStatic
    fun start(context: Context = requireApp()): Intent? {
        requireInstalled()

        if (enableVpn) {
            val prepare = VpnService.prepare(context)
            if (prepare != null) {
                return prepare
            }
            context.startForegroundServiceCompat(TunService::class.intent)
        } else {
            context.startForegroundServiceCompat(ClashService::class.intent)
        }
        return null
    }

    /** Request Clash stop via self-broadcast (same path as stock UI). */
    @JvmStatic
    fun stop(context: Context = requireApp()) {
        requireInstalled()
        context.sendBroadcastSelf(Intent(Intents.ACTION_CLASH_REQUEST_STOP))
    }

    // region Profile API

    suspend fun createProfile(
        type: Profile.Type,
        name: String,
        source: String = "",
        ageSecretKey: String? = null,
    ): UUID = withProfile {
        create(type, name, source, ageSecretKey)
    }

    /**
     * Create a URL profile, commit (fetch + validate), and return its UUID.
     * Does not automatically set active — call [setActive] when ready.
     */
    suspend fun importUrlProfile(
        name: String,
        url: String,
        ageSecretKey: String? = null,
    ): UUID {
        val uuid = createProfile(Profile.Type.Url, name, url, ageSecretKey)
        commitProfile(uuid)
        return uuid
    }

    suspend fun commitProfile(uuid: UUID) = withProfile {
        commit(uuid)
    }

    suspend fun deleteProfile(uuid: UUID) = withProfile {
        delete(uuid)
    }

    /** Reset local-from-0 used counters for a profile (subscription quota unchanged). */
    suspend fun resetLocalTraffic(uuid: UUID) = withProfile {
        resetLocalTraffic(uuid)
    }

    suspend fun updateProfile(uuid: UUID) = withProfile {
        update(uuid)
    }

    suspend fun queryProfiles(): List<Profile> = withProfile {
        queryAll()
    }

    suspend fun queryActiveProfile(): Profile? = withProfile {
        queryActive()
    }

    suspend fun setActive(profile: Profile) = withProfile {
        setActive(profile)
    }

    suspend fun setActive(uuid: UUID) {
        val profile = withProfile { queryByUUID(uuid) }
            ?: error("Profile not found: $uuid")
        setActive(profile)
    }

    // endregion

    // region Proxy / tunnel API

    suspend fun queryTunnelState(): TunnelState = withClash {
        queryTunnelState()
    }

    suspend fun queryTrafficTotal(): Long = withClash {
        queryTrafficTotal()
    }

    suspend fun queryTrafficNow(): Long = withClash {
        queryTrafficNow()
    }

    suspend fun queryProxyGroupNames(
        excludeNotSelectable: Boolean = true,
    ): List<String> = withClash {
        queryProxyGroupNames(excludeNotSelectable)
    }

    suspend fun queryProxyGroup(
        name: String,
        sort: ProxySort = ProxySort.Default,
    ): ProxyGroup = withClash {
        queryProxyGroup(name, sort)
    }

    suspend fun selectProxy(group: String, name: String): Boolean = withClash {
        patchSelector(group, name)
    }

    suspend fun healthCheck(group: String) = withClash {
        healthCheck(group)
    }

    suspend fun healthCheckAll() {
        val names = queryProxyGroupNames(excludeNotSelectable = false)
        names.forEach { healthCheck(it) }
    }

    // endregion

    /**
     * Execute a block against [IClashManager] with DeadObjectException retry.
     * Prefer the typed helpers above when possible.
     */
    suspend fun <T> withClash(
        context: CoroutineContext = Dispatchers.IO,
        block: suspend IClashManager.() -> T,
    ): T {
        while (true) {
            val remote = requireSession().remote.get()
            val client = remote.clash()
            try {
                return withContext(context) { client.block() }
            } catch (e: DeadObjectException) {
                Log.w("ClashRuntime: IClashManager dead, retrying")
                requireSession().remote.reset(remote)
            }
        }
    }

    /**
     * Execute a block against [IProfileManager] with DeadObjectException retry.
     */
    suspend fun <T> withProfile(
        context: CoroutineContext = Dispatchers.IO,
        block: suspend IProfileManager.() -> T,
    ): T {
        while (true) {
            val remote = requireSession().remote.get()
            val client = remote.profile()
            try {
                return withContext(context) { client.block() }
            } catch (e: DeadObjectException) {
                Log.w("ClashRuntime: IProfileManager dead, retrying")
                requireSession().remote.reset(remote)
            }
        }
    }

    private fun requireInstalled() {
        check(app != null && session != null) {
            "ClashRuntime.install(application) must be called first"
        }
    }

    private fun requireApp(): Application =
        app ?: error("ClashRuntime.install(application) must be called first")

    private fun requireSession(): RemoteSession =
        session ?: error("ClashRuntime.install(application) must be called first")

    private fun requireEventHub(): EventHub =
        eventHub ?: error("ClashRuntime.install(application) must be called first")
}
