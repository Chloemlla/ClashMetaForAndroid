package com.github.kr328.clash.service

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit

object ProfileProcessor {
    private val profileLock = Mutex()
    private val processLock = Mutex()

    suspend fun apply(context: Context, uuid: UUID, callback: IFetchObserver? = null) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = snapshotPending(context, uuid)

                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })

                val force = snapshot.type != Profile.Type.File
                val subscriptionInfo = fetchProfile(context, snapshot.source, force, callback)
                val useLocalTraffic = ServiceStore(context)
                    .getLocalSubscriptionTraffic(snapshot.uuid)

                profileLock.withLock {
                    val old = ImportedDao().queryByUUID(snapshot.uuid)
                    val updateInterval = subscriptionInfo?.subUpdateInterval
                        ?.takeIf { old == null && snapshot.interval == 0L }
                        ?: snapshot.interval

                    // Local mode: bill used traffic from 0 B via LocalSubscriptionTrafficStore,
                    // but still persist upstream total/expire so the config UI can show a
                    // progress bar against the subscription quota.
                    // Upstream mode: persist full subscription-userinfo into Imported.
                    val upload: Long
                    val download: Long
                    val total: Long
                    val expire: Long
                    if (useLocalTraffic) {
                        upload = 0
                        download = 0
                        total = subscriptionInfo?.subTotal ?: old?.total ?: 0
                        expire = subscriptionInfo?.subExpire ?: old?.expire ?: 0
                    } else {
                        upload = subscriptionInfo?.subUpload ?: 0
                        download = subscriptionInfo?.subDownload ?: 0
                        total = subscriptionInfo?.subTotal ?: 0
                        expire = subscriptionInfo?.subExpire ?: 0
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
        withContext(NonCancellable) {
            processLock.withLock {
                try {
                    val snapshot = snapshotPending(context, uuid)

                    Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })
                    fetchProfile(
                        context = context,
                        source = snapshot.source,
                        force = snapshot.type != Profile.Type.File,
                        callback = null,
                    )
                } finally {
                    context.processingDir.deleteRecursively()
                }
            }
        }
    }

    suspend fun update(context: Context, uuid: UUID, callback: IFetchObserver?) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val imported =
                        ImportedDao().queryByUUID(uuid) ?: throw IllegalArgumentException("profile $uuid not found")

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.importedDir.resolve(imported.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    imported
                }

                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })

                val subscriptionInfo = fetchProfile(context, snapshot.source, true, callback)
                val useLocalTraffic = ServiceStore(context)
                    .getLocalSubscriptionTraffic(snapshot.uuid)

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
        var subscriptionInfo: FetchStatus? = null
        var cb = callback

        Clash.fetchAndValid(context.processingDir, source, force) {
            if (it.action == FetchStatus.Action.SubscriptionInfo) {
                subscriptionInfo = it
                return@fetchAndValid
            }

            try {
                cb?.updateStatus(it)
            } catch (e: Exception) {
                cb = null

                Log.w("Report fetch status: $e", e)
            }
        }.await()

        return subscriptionInfo
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

    private fun Pending.enforceFieldValid() {
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

}
