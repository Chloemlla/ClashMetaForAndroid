package com.github.kr328.clash.service

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.store.LocalSubscriptionTrafficStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.processingDir
import com.github.kr328.clash.service.util.replaceDirectoryAtomically
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.*
import java.util.concurrent.TimeUnit

// Serializes "Clash.setAgeSecretKey + fetch/load" pairs: the age secret lives in a
// process-global native variable, so concurrent profile operations must not overwrite
// each other's key between the set and the use (B-168). ConfigurationModule (active
// profile load) and ProfileProcessor (download) share this lock.
internal val ageSecretLock = Mutex()

object ProfileProcessor {
    private val profileLock = Mutex()
    private val processLock = Mutex()

    private val PROCESS_LOCK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(130)
    private val FETCH_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(120)
    private val PROGRESS_REPORT_INTERVAL_MS = 200L

    suspend fun apply(context: Context, uuid: UUID, callback: IFetchObserver? = null) {
        withProcessLock {
            val snapshot = snapshotPending(context, uuid)

            val subscriptionInfo = ageSecretLock.withLock {
                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })

                fetchProfile(
                    context = context,
                    source = snapshot.source,
                    force = snapshot.type != Profile.Type.File,
                    callback = callback,
                )
            }
            val useLocalTraffic = ServiceStore(context)
                .getLocalSubscriptionTraffic(snapshot.uuid)

            // The download above is cancellable; only the DB commit and directory
            // swap must survive cancellation once the fetch succeeded.
            withContext(NonCancellable) {
                profileLock.withLock {
                    val old = ImportedDao().queryByUUID(snapshot.uuid)
                    val updateInterval = subscriptionInfo?.subUpdateInterval
                        ?.takeIf { old == null && snapshot.interval == 0L }
                        ?: snapshot.interval

                    // Local mode: bill used traffic from 0 B via LocalSubscriptionTrafficStore,
                    // but still persist upstream total/expire so the config UI can show a
                    // progress bar against the subscription quota.
                    // Upstream mode: persist full subscription-userinfo into Imported.
                    // When userinfo is absent (e.g. a cloned Type.File profile), fall back to
                    // the values preserved in the pending snapshot instead of zeroing them (B-170).
                    val upload: Long
                    val download: Long
                    val total: Long
                    val expire: Long
                    if (useLocalTraffic) {
                        upload = 0
                        download = 0
                        total = subscriptionInfo?.subTotal ?: snapshot.total.takeIf { it != 0L } ?: old?.total ?: 0
                        expire = subscriptionInfo?.subExpire ?: snapshot.expire.takeIf { it != 0L } ?: old?.expire ?: 0
                    } else {
                        upload = subscriptionInfo?.subUpload ?: snapshot.upload
                        download = subscriptionInfo?.subDownload ?: snapshot.download
                        total = subscriptionInfo?.subTotal ?: snapshot.total
                        expire = subscriptionInfo?.subExpire ?: snapshot.expire
                    }

                    val imported = Imported(
                        uuid = snapshot.uuid,
                        name = snapshot.name,
                        type = snapshot.type,
                        source = snapshot.source,
                        interval = updateInterval,
                        upload = upload,
                        download = download,
                        total = total,
                        expire = expire,
                        createdAt = old?.createdAt ?: System.currentTimeMillis(),
                        ageSecretKey = snapshot.ageSecretKey,
                    )

                    // The transaction consumes the pending row and writes the imported row
                    // atomically; the directory moves after it are idempotent.
                    if (Database.database.commitImported(imported, snapshot)) {
                        replaceDirectoryAtomically(
                            context.processingDir,
                            context.importedDir.resolve(snapshot.uuid.toString()),
                        )
                        context.pendingDir.resolve(snapshot.uuid.toString()).deleteRecursively()
                        context.sendProfileChanged(snapshot.uuid)
                    } else {
                        // Pending row was consumed/edited while we were downloading (e.g.
                        // release() from a back press): the downloaded content is stale,
                        // so drop it instead of leaking it in processingDir.
                        Log.w("Apply ${snapshot.uuid}: pending changed during download, drop result")
                        context.processingDir.deleteRecursively()
                    }
                }
            }
        }
    }

    /**
     * Validate the current pending profile with the same kernel path used by [apply],
     * without replacing the imported profile or consuming the pending edit.
     */
    suspend fun validate(context: Context, uuid: UUID) {
        withProcessLock {
            try {
                val snapshot = snapshotPending(context, uuid)

                ageSecretLock.withLock {
                    Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })
                    fetchProfile(
                        context = context,
                        source = snapshot.source,
                        force = snapshot.type != Profile.Type.File,
                        callback = null,
                    )
                }
            } finally {
                context.processingDir.deleteRecursively()
            }
        }
    }

    suspend fun update(context: Context, uuid: UUID, callback: IFetchObserver?) {
        withProcessLock {
            val snapshot = profileLock.withLock {
                val imported =
                    ImportedDao().queryByUUID(uuid) ?: throw IllegalArgumentException("profile $uuid not found")

                // Same input validation as the manual import path (B-194): rows that
                // arrived via legacy/migration bundles were never checked.
                imported.enforceFieldValid()

                context.processingDir.deleteRecursively()
                context.processingDir.mkdirs()

                context.importedDir.resolve(imported.uuid.toString())
                    .copyRecursively(context.processingDir, overwrite = true)

                imported
            }

            val subscriptionInfo = ageSecretLock.withLock {
                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })

                fetchProfile(context, snapshot.source, true, callback)
            }
            val useLocalTraffic = ServiceStore(context)
                .getLocalSubscriptionTraffic(snapshot.uuid)

            withContext(NonCancellable) {
                profileLock.withLock {
                    val imported = ImportedDao().queryByUUID(snapshot.uuid)
                    if (imported != null) {
                        if (useLocalTraffic) {
                            // Refresh quota/expiry for the progress bar; never bill from userinfo.
                            val nextTotal = subscriptionInfo?.subTotal
                            val nextExpire = subscriptionInfo?.subExpire
                            if (nextTotal != null || nextExpire != null) {
                                ImportedDao().update(
                                    imported.copy(
                                        total = nextTotal ?: imported.total,
                                        expire = nextExpire ?: imported.expire,
                                    )
                                )
                            }
                        } else {
                            val upload = subscriptionInfo?.subUpload
                            if (upload != null) {
                                ImportedDao().update(
                                    imported.copy(
                                        upload = upload,
                                        download = subscriptionInfo.subDownload ?: 0,
                                        total = subscriptionInfo.subTotal ?: 0,
                                        expire = subscriptionInfo.subExpire ?: 0,
                                    )
                                )
                            }
                        }

                        // Swap dir after DB so a mid-crash never leaves NEW dir + OLD
                        // metadata; notify only after both are consistent.
                        replaceDirectoryAtomically(
                            context.processingDir,
                            context.importedDir.resolve(snapshot.uuid.toString()),
                        )

                        context.sendProfileChanged(snapshot.uuid)
                    }
                }
            }
        }
    }

    private suspend fun fetchProfile(
        context: Context,
        source: String,
        force: Boolean,
        callback: IFetchObserver?,
    ): FetchStatus? {
        return try {
            withTimeout(FETCH_TIMEOUT_MS) {
                var subscriptionInfo: FetchStatus? = null
                var lastReportAt = 0L

                Clash.fetchAndValid(context.processingDir, source, force) {
                    if (it.action == FetchStatus.Action.SubscriptionInfo) {
                        subscriptionInfo = it
                        return@fetchAndValid
                    }

                    // Throttle cross-process progress callbacks: the UI only needs a
                    // handful per second, and a saturated binder must not stall the fetch.
                    val now = SystemClock.elapsedRealtime()
                    if (callback != null && now - lastReportAt >= PROGRESS_REPORT_INTERVAL_MS) {
                        lastReportAt = now
                        try {
                            callback.updateStatus(it)
                        } catch (e: Exception) {
                            // A dying/rotating UI process must not silence the whole
                            // progress stream: skip this one event; a re-bound observer
                            // receives fresh updates (B-186).
                            Log.w("Report fetch status: $e", e)
                        }
                    }
                }.await()

                subscriptionInfo
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException(
                "Fetch profile timed out after ${FETCH_TIMEOUT_MS}ms",
                e,
            )
        }
    }

    /**
     * Runs [block] while holding [processLock], waiting at most
     * [PROCESS_LOCK_TIMEOUT_MS] for a stuck predecessor (A-38). The wait itself is
     * cancellable; a timeout surfaces as "busy" instead of hanging the caller forever.
     */
    private suspend fun <T> withProcessLock(block: suspend () -> T): T {
        try {
            withTimeout(PROCESS_LOCK_TIMEOUT_MS) {
                processLock.lock()
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Another profile operation is in progress", e)
        }

        try {
            return block()
        } finally {
            processLock.unlock()
        }
    }

    suspend fun delete(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                val pending = context.pendingDir.resolve(uuid.toString())
                val imported = context.importedDir.resolve(uuid.toString())

                pending.deleteRecursively()
                imported.deleteRecursively()

                val serviceStore = ServiceStore(context)
                if (serviceStore.activeProfile == uuid) {
                    serviceStore.activeProfile = null
                }

                serviceStore.clearLocalSubscriptionTraffic(uuid)
                LocalSubscriptionTrafficStore(context).clear(uuid)

                ImportedDao().remove(uuid)
                PendingDao().remove(uuid)

                context.sendProfileChanged(uuid)
            }
        }
    }

    suspend fun release(context: Context, uuid: UUID): Boolean {
        return withContext(NonCancellable) {
            profileLock.withLock {
                PendingDao().remove(uuid)

                if (!ImportedDao().exists(uuid)) {
                    ServiceStore(context).clearLocalSubscriptionTraffic(uuid)
                }

                context.pendingDir.resolve(uuid.toString()).deleteRecursively()
            }
        }
    }

    suspend fun active(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                if (ImportedDao().exists(uuid)) {
                    val store = ServiceStore(context)

                    store.activeProfile = uuid

                    context.sendProfileChanged(uuid)
                }
            }
        }
    }

    private suspend fun snapshotPending(context: Context, uuid: UUID): Pending {
        return profileLock.withLock {
            val pending = PendingDao().queryByUUID(uuid)
                ?: throw IllegalArgumentException("profile $uuid not found")

            pending.enforceFieldValid()

            context.processingDir.deleteRecursively()
            context.processingDir.mkdirs()

            context.pendingDir.resolve(pending.uuid.toString())
                .copyRecursively(context.processingDir, overwrite = true)

            pending
        }
    }

    private fun enforceFieldValid(name: String, source: String, type: Profile.Type, interval: Long) {
        val scheme = Uri.parse(source)?.scheme?.lowercase(Locale.getDefault())

        when {
            name.isBlank() -> throw IllegalArgumentException("Empty name")

            source.isEmpty() && type != Profile.Type.File -> throw IllegalArgumentException("Invalid url")

            source.isNotEmpty() && scheme != "https" && scheme != "http" && scheme != "content" -> throw IllegalArgumentException(
                "Unsupported url $source"
            )

            interval != 0L && TimeUnit.MILLISECONDS.toMinutes(interval) < 15 -> throw IllegalArgumentException("Invalid interval")
        }
    }

    private fun Pending.enforceFieldValid() {
        enforceFieldValid(name, source, type, interval)
    }

    private fun Imported.enforceFieldValid() {
        enforceFieldValid(name, source, type, interval)
    }

}
