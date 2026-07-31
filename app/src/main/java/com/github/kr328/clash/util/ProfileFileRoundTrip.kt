package com.github.kr328.clash.util

import com.github.kr328.clash.remote.FilesClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

object ProfileFileRoundTrip {
    sealed class Result {
        object Staged : Result()
        data class Rejected(
            val cause: Exception,
            val rollbackFailure: Exception? = null,
        ) : Result()
    }

    suspend fun validateAndStage(
        uuid: UUID,
        documentId: String,
        client: FilesClient,
        session: ProfileFileEditor,
    ): Result {
        val hadPendingChanges = withProfile {
            queryByUUID(uuid)?.pending == true
        }

        return try {
            client.copyDocument(documentId, session.editedUri)
            withProfile { validate(uuid) }

            Result.Staged
        } catch (cause: Exception) {
            val rollbackFailure = withContext(NonCancellable) {
                runCatching {
                    if (hadPendingChanges) {
                        client.copyDocument(documentId, session.originalUri)
                    } else {
                        withProfile { release(uuid) }
                    }
                }.exceptionOrNull()
            }

            if (cause is CancellationException) {
                rollbackFailure?.let(cause::addSuppressed)
                throw cause
            }

            Result.Rejected(cause, rollbackFailure)
        }
    }
}
