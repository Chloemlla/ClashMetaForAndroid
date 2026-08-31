package com.github.kr328.clash.core.bridge

import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CompletableDeferred

@Keep
object Bridge {
    external fun nativeReset()
    external fun nativeForceGc()
    external fun nativeSuspend(suspend: Boolean)
    external fun nativeQueryTunnelState(): String
    external fun nativeQueryTrafficNow(): Long
    external fun nativeQueryTrafficTotal(): Long
    external fun nativeNotifyDnsChanged(dnsList: String)
    external fun nativeNotifyTimeZoneChanged(name: String, offset: Int)
    external fun nativeNotifyInstalledAppChanged(uidList: String)
    external fun nativeStartTun(fd: Int, stack: String, gateway: String, portal: String, dns: String, cb: TunInterface)
    external fun nativeStopTun()
    external fun nativeStartHttp(listenAt: String): String?
    external fun nativeStopHttp()
    external fun nativeQueryGroupNames(excludeNotSelectable: Boolean): String
    external fun nativeQueryGroup(name: String, sort: String): String?
    external fun nativeQueryGroupNow(name: String): String?
    external fun nativeQueryGroupDelays(name: String): String
    external fun nativeHasProviders(): Boolean
    external fun nativeQueryDashboardSummary(preferred: String, excludeNotSelectable: Boolean): String
    external fun nativeQueryMemoryUsage(): Long
    external fun nativeHealthCheck(completable: CompletableDeferred<Unit>, name: String)
    external fun nativeHealthCheckAll()
    external fun nativePatchSelector(selector: String, name: String): Boolean
    external fun nativeFetchAndValid(
        completable: FetchCallback,
        path: String,
        url: String,
        force: Boolean
    )

    external fun nativeLoad(completable: CompletableDeferred<Unit>, path: String)
    external fun nativeQueryProviders(): String
    external fun nativeUpdateProvider(
        completable: CompletableDeferred<Unit>,
        type: String,
        name: String
    )
    external fun nativeUpdateAdblock(
        completable: CompletableDeferred<Unit>,
        profileDir: String,
        proxy: String
    )
    external fun nativeAdblockReady(profileDir: String): Boolean

    external fun nativeReadOverride(slot: Int): String
    external fun nativeWriteOverride(slot: Int, content: String)
    external fun nativeClearOverride(slot: Int)
    external fun nativeQueryConfiguration(): String
    external fun nativeSubscribeLogcat(callback: LogcatInterface): Long
    external fun nativeUnsubscribeLogcat(token: Long)
    external fun nativeSubscribeConnections(callback: ConnectionsInterface, intervalMs: Long): Long
    external fun nativeUnsubscribeConnections(token: Long)
    external fun nativeSubscribeDns(callback: DnsInterface): Long
    external fun nativeUnsubscribeDns(token: Long)
    external fun nativeSubscribeAdblock(callback: AdblockInterface): Long
    external fun nativeUnsubscribeAdblock(token: Long)
    external fun nativeQueryAdblockStats(): String
    external fun nativeClearAdblockHits()
    external fun nativeSubscribeHttp(callback: HttpCaptureInterface): Long
    external fun nativeUnsubscribeHttp(token: Long)
    external fun nativeCloseConnection(id: String)
    external fun nativeCloseAllConnections()
    external fun nativeCoreVersion(): String

    external fun nativeSetAgeSecretKey(key: String?)
    external fun nativeGenX25519KeyPair(): String?
    external fun nativeGenHybridKeyPair(): String?
    external fun nativeVeritySecretKeys(secretKeys: String): Boolean
    external fun nativeToPublicKeys(secretKeys: String): String?
    external fun nativeVerityPublicKeys(publicKeys: String): Boolean

    private external fun nativeInit(home: String, versionName: String, sdkVersion: Int)

    @Volatile
    private var initResult: Result<Unit>? = null

    init {
        System.loadLibrary("bridge")
    }

    @Synchronized
    fun init(context: Context): Result<Unit> {
        initResult?.let { return it }

        val result = runCatching {
            val home = context.filesDir.resolve("clash").apply { mkdirs() }.absolutePath
            val versionName = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            val sdkVersion = Build.VERSION.SDK_INT

            Log.d("Home = $home")

            nativeInit(home, versionName, sdkVersion)
        }

        // A failed init is not cached so a transient failure can be retried on the next start
        // instead of poisoning the object for the rest of the process lifetime.
        if (result.isSuccess) initResult = result

        return result
    }
}