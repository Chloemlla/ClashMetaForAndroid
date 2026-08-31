package com.github.kr328.clash.sdk

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.RemoteException
import android.os.TransactionTooLargeException
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
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.util.sendBroadcastSelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
    private const val MAX_BINDER_RETRIES = 5
    private const val BINDER_RETRY_BASE_DELAY_MS = 100L

    @Volatile
    private var app: Application? = null

    @Volatile
    private var config: ClashRuntimeConfig = ClashRuntimeConfig()

    @Volatile
    private var session: RemoteSession? = null

    // Process-level resident hub: the event stream is created with the facade, so subscribing
    // to [events] before [install] is legal and simply yields no events until receivers register.
    private val eventHub: EventHub = EventHub()

    @Volatile
    private var enableVpn: Boolean = true

    /** Runtime lifecycle events; empty until receivers are registered via [bind]. */
    val events: Flow<ClashRuntimeEvent>
        get() = eventHub.events

    /** Best-effort running flag from last received start/stop broadcasts. */
    val isRunning: Boolean
        get() = eventHub.clashRunning

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
            eventHub.attach(application)
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
        eventHub.register()
        requireSession().bind()
    }

    /** Unbind remote service and drop event receivers. */
    @JvmStatic
    fun unbind() {
        eventHub.unregister()
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
    ): UUID = withProfileWrite {
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

    suspend fun commitProfile(uuid: UUID) = withProfileWrite {
        commit(uuid)
    }

    suspend fun deleteProfile(uuid: UUID) = withProfileWrite {
        delete(uuid)
    }

    /** Reset local-from-0 used counters for a profile (subscription quota unchanged). */
    suspend fun resetLocalTraffic(uuid: UUID) = withProfileWrite {
        resetLocalTraffic(uuid)
    }

    suspend fun updateProfile(uuid: UUID) = withProfileWrite {
        update(uuid)
    }

    suspend fun queryProfiles(): List<Profile> = withProfile {
        queryAll()
    }

    suspend fun queryActiveProfile(): Profile? = withProfile {
        queryActive()
    }

    suspend fun setActive(profile: Profile) = withProfileWrite {
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

    suspend fun selectProxy(group: String, name: String): Boolean = withClashWrite {
        patchSelector(group, name)
    }

    suspend fun healthCheck(group: String) = withClashWrite {
        healthCheck(group)
    }

    suspend fun healthCheckAll() {
        val names = queryProxyGroupNames(excludeNotSelectable = false)
        names.forEach { healthCheck(it) }
    }

    // endregion

    /**
     * Execute a read-only / idempotent block against [IClashManager] with bounded
     * retry and exponential backoff. Safe because a retry cannot double-apply a mutation.
     * Prefer [withClashWrite] for mutating calls.
     */
    suspend fun <T> withClash(
        context: CoroutineContext = Dispatchers.IO,
        block: suspend IClashManager.() -> T,
    ): T = withClashImpl(context, idempotent = true, block)

    /**
     * Execute a mutating block against [IClashManager] exactly once — never automatically
     * retried, because a retry could apply the mutation a second time. On failure throws
     * [ClashRuntimeRemoteException] with `mayHaveExecuted = true`.
     */
    suspend fun <T> withClashWrite(
        context: CoroutineContext = Dispatchers.IO,
        block: suspend IClashManager.() -> T,
    ): T = withClashImpl(context, idempotent = false, block)

    /**
     * Execute a read-only / idempotent block against [IProfileManager] with bounded
     * retry and exponential backoff. Prefer [withProfileWrite] for mutating calls.
     */
    suspend fun <T> withProfile(
        context: CoroutineContext = Dispatchers.IO,
        block: suspend IProfileManager.() -> T,
    ): T = withProfileImpl(context, idempotent = true, block)

    /**
     * Execute a mutating block against [IProfileManager] exactly once — never automatically
     * retried, because a retry could apply the mutation a second time. On failure throws
     * [ClashRuntimeRemoteException] with `mayHaveExecuted = true`.
     */
    suspend fun <T> withProfileWrite(
        context: CoroutineContext = Dispatchers.IO,
        block: suspend IProfileManager.() -> T,
    ): T = withProfileImpl(context, idempotent = false, block)

    private suspend fun <T> withClashImpl(
        context: CoroutineContext,
        idempotent: Boolean,
        block: suspend IClashManager.() -> T,
    ): T = withRemoteImpl(context, "IClashManager", idempotent, { clash() }, block)

    private suspend fun <T> withProfileImpl(
        context: CoroutineContext,
        idempotent: Boolean,
        block: suspend IProfileManager.() -> T,
    ): T = withRemoteImpl(context, "IProfileManager", idempotent, { profile() }, block)

    private suspend fun <C, T> withRemoteImpl(
        context: CoroutineContext,
        operation: String,
        idempotent: Boolean,
        client: suspend IRemoteService.() -> C,
        block: suspend C.() -> T,
    ): T {
        var attempt = 0
        while (true) {
            val remote = requireSession().remote.get()
            try {
                val target = remote.client()
                return withContext(context) { target.block() }
            } catch (e: TransactionTooLargeException) {
                // Not a transient failure: the payload exceeds the Binder buffer and every
                // retry would rebuild the same oversized transaction. The transaction never
                // reached the service, so it was definitely not executed.
                throw ClashRuntimeRemoteException(
                    mayHaveExecuted = false,
                    "ClashRuntime $operation failed: transaction too large",
                    e,
                )
            } catch (e: RemoteException) {
                if (!idempotent) {
                    // Mutation: never retry — the service may have executed it before dying.
                    throw ClashRuntimeRemoteException(
                        mayHaveExecuted = true,
                        "ClashRuntime $operation write failed; it may or may not have been applied",
                        e,
                    )
                }
                attempt += 1
                if (attempt > MAX_BINDER_RETRIES) {
                    throw ClashRuntimeRemoteException(
                        mayHaveExecuted = false,
                        "ClashRuntime $operation failed after $MAX_BINDER_RETRIES retries",
                        e,
                    )
                }
                Log.w("ClashRuntime: $operation remote dead, retrying ($attempt/$MAX_BINDER_RETRIES)")
                requireSession().remote.reset(remote)
                delay(BINDER_RETRY_BASE_DELAY_MS * attempt)
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
}

/**
 * Thrown when a ClashRuntime Binder call fails.
 *
 * @property mayHaveExecuted whether the failed call was a mutation that may have reached the
 *   service before the connection broke. Always `false` for read/idempotent calls whose retries
 *   were exhausted; `true` for write calls, where the caller must decide whether to reconcile.
 */
class ClashRuntimeRemoteException(
    val mayHaveExecuted: Boolean,
    message: String,
    cause: RemoteException,
) : RuntimeException(message, cause)
