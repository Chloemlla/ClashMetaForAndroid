package com.github.kr328.clash.service.document

import android.content.Context
import android.provider.DocumentsContract
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.replaceDirectoryAtomically
import java.io.FileNotFoundException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Picker(private val context: Context) {
    private val cloneLocks = ConcurrentHashMap<UUID, Mutex>()

    suspend fun list(path: Path): List<Document> {
        if (path.uuid == null) {
            return ImportedDao().queryAllUUIDs().map {
                pick(path.copy(uuid = it), false)
            }
        }

        if (path.scope == null) {
            return listOf(Path.Scope.Configuration, Path.Scope.Providers).map {
                pick(path.copy(scope = it), false)
            }
        }

        val parent = pick(path, false)

        if (parent !is FileDocument)
            return emptyList()

        return (parent.file.list() ?: emptyArray()).map {
            pick(path.copy(relative = (path.relative ?: emptyList()) + it), false)
        }
    }

    suspend fun pick(path: Path, writable: Boolean): Document {
        if (path.uuid == null) {
            return VirtualDocument(
                "",
                context.getString(R.string.clash_meta_for_android),
                DocumentsContract.Document.MIME_TYPE_DIR,
                0,
                0,
                setOf(Flag.Virtual),
            )
        }

        val imported = ImportedDao().queryByUUID(path.uuid)
        val pending = PendingDao().queryByUUID(path.uuid)

        if (path.scope == null) {
            if (writable)
                throw IllegalArgumentException("invalid open mode")

            return VirtualDocument(
                id = path.uuid.toString(),
                name = pending?.name ?: imported?.name
                ?: throw FileNotFoundException("profile not found"),
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                size = 0,
                updatedAt = 0,
                flags = setOf(Flag.Virtual),
            )
        }

        if (path.relative == null) {
            if (path.scope == Path.Scope.Configuration) {
                val type = pending?.type ?: imported?.type
                ?: throw FileNotFoundException("profile not found")

                if (writable && type != Profile.Type.File)
                    throw IllegalArgumentException("invalid open mode")

                // Clone only after all validation above, so a rejected open never
                // leaves an orphan pending row or a copied directory behind.
                val pendingAfterClone = if (writable) cloneToPending(path.uuid) else pending

                val file = when {
                    pendingAfterClone != null -> context.pendingDir.resolve(pendingAfterClone.uuid.toString())
                    imported != null -> context.importedDir.resolve(imported.uuid.toString())
                    else -> throw FileNotFoundException("profile not found")
                }.resolve("config.yaml")

                val flags: Set<Flag> = when {
                    file.exists() && !file.canRead() -> setOf(Flag.Unreadable)
                    type == Profile.Type.File -> setOf(Flag.Writable)
                    else -> emptySet()
                }

                return FileDocument(
                    file = file,
                    flags = flags,
                    idOverride = Paths.CONFIGURATION_ID,
                    nameOverride = context.getString(R.string.configuration_yaml)
                )
            } else {
                return FileDocument(
                    file = when {
                        pending != null -> context.pendingDir.resolve(pending.uuid.toString())
                        imported != null -> context.importedDir.resolve(imported.uuid.toString())
                        else -> throw FileNotFoundException("profile not found")
                    }.resolve("providers"),
                    idOverride = Paths.PROVIDERS_ID,
                    nameOverride = context.getString(R.string.provider_files),
                    flags = setOf(Flag.Virtual)
                )
            }
        }

        if (path.scope != Path.Scope.Providers)
            throw FileNotFoundException("invalid path")

        val file = if (writable) {
            val pendingAfterClone = cloneToPending(path.uuid)
            context.pendingDir.resolve(pendingAfterClone.uuid.toString())
                .resolve("providers")
                .resolve(path.relative.joinToString(separator = "/"))
        } else {
            when {
                pending != null -> context.pendingDir.resolve(pending.uuid.toString())
                imported != null -> context.importedDir.resolve(imported.uuid.toString())
                else -> throw FileNotFoundException("profile not found")
            }.resolve("providers").resolve(path.relative.joinToString(separator = "/"))
        }

        val flags = if (file.exists() && !file.canRead())
            setOf(Flag.Unreadable, Flag.Deletable)
        else
            setOf(Flag.Writable, Flag.Deletable)

        return FileDocument(file = file, flags = flags)
    }

    private suspend fun cloneToPending(uuid: UUID): Pending {
        val lock = cloneLocks.computeIfAbsent(uuid) { Mutex() }

        return lock.withLock {
            PendingDao().queryByUUID(uuid)?.let { return@withLock it }

            val imported = ImportedDao().queryByUUID(uuid)
                ?: throw FileNotFoundException("profile not found")

            // Copy into a staging directory and swap it in before inserting the row, so
            // a mid-copy crash never leaves a Pending row pointing at an empty directory.
            replaceDirectoryAtomically(
                context.importedDir.resolve(uuid.toString()),
                context.pendingDir.resolve(uuid.toString()),
            )

            val pending = Pending(
                uuid = imported.uuid,
                name = imported.name,
                type = imported.type,
                source = imported.source,
                interval = imported.interval,
                upload = imported.upload,
                download = imported.download,
                total = imported.total,
                expire = imported.expire,
                ageSecretKey = imported.ageSecretKey,
            )
            PendingDao().insert(pending)
            pending
        }
    }
}
