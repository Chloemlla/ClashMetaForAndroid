@file:Suppress("BlockingMethodInNonBlockingContext")

package com.github.kr328.clash

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.design.FilesDesign
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.FilesClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.fileName
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.util.*
import java.util.concurrent.TimeUnit

class FilesActivity : BaseActivity<FilesDesign>() {
    override suspend fun main() {
        val uuid = intent.uuid ?: return finish()
        val profile = withProfile { queryByUUID(uuid) } ?: return finish()
        val root = uuid.toString()

        val design = FilesDesign(this)
        val client = FilesClient(this)
        design.configurationEditable = profile.type == Profile.Type.File
        val fileActions = ProfileFileActions(
            activity = this,
            uuid = uuid,
            root = root,
            configurationEditable = design.configurationEditable,
            client = client,
            design = design,
        )
        val stack = Stack<String>()

        setContentDesign(design)

        val ticker = ticker(TimeUnit.MINUTES.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        // ActivityStart covers first enter (queued from onStart during setContentDesign)
                        // and return-from-background. Do not refresh on ActivityStop — work is invisible.
                        Event.ActivityStart -> {
                            design.fetch(client, stack, root)
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    try {
                        when (it) {
                            FilesDesign.Request.PopStack -> {
                                if (stack.empty()) {
                                    finish()
                                } else {
                                    stack.pop()
                                }
                            }
                            is FilesDesign.Request.OpenDirectory -> {
                                stack.push(it.file.id)
                            }
                            is FilesDesign.Request.OpenFile -> {
                                fileActions.open(it.file)
                            }
                            is FilesDesign.Request.DeleteFile -> {
                                client.deleteDocument(it.file.id)
                            }
                            is FilesDesign.Request.RenameFile -> {
                                val newName = design.requestFileName(it.file.name)

                                client.renameDocument(it.file.id, newName)
                            }
                            is FilesDesign.Request.ImportFile -> {
                                val uri: Uri? = startActivityForResult(
                                    ActivityResultContracts.GetContent(),
                                    "*/*"
                                )

                                if (uri != null) {
                                    val file = it.file

                                    if (file == null) {
                                        val name = design.requestFileName(uri.fileName ?: "File")

                                        client.importDocument(stack.last(), uri, name)
                                    } else if (fileActions.isConfiguration(file)) {
                                        fileActions.importEdited(file, uri)
                                    } else {
                                        client.copyDocument(file.id, uri)
                                    }
                                }
                            }
                            is FilesDesign.Request.ExportFile -> {
                                val uri: Uri? = startActivityForResult(
                                    ActivityResultContracts.CreateDocument(
                                        fileActions.mimeType(it.file)
                                    ),
                                    it.file.name,
                                )

                                if (uri != null) {
                                    client.copyDocument(uri, it.file.id)
                                }
                            }
                            is FilesDesign.Request.ShowOutline -> {
                                fileActions.showOutline(it.file)
                            }
                        }
                    } catch (e: Exception) {
                        design.showExceptionToast(e)
                    }

                    design.fetch(client, stack, root)
                }
                if (activityStarted) {
                    ticker.onReceive {
                        design.updateElapsed()
                    }
                }
            }
        }
    }

    override fun onBackPressedCompat(): Boolean {
        design?.requests?.trySend(FilesDesign.Request.PopStack)
        return true
    }

    private suspend fun FilesDesign.fetch(client: FilesClient, stack: Stack<String>, root: String) {
        val documentId = stack.lastOrNull() ?: root
        val files = if (stack.empty()) {
            val list = client.list(documentId)
            val config = list.firstOrNull { it.id.endsWith("config.yaml") }

            if (config == null || config.size > 0) list else listOf(config)
        } else {
            client.list(documentId)
        }

        swapFiles(files, stack.empty())
    }
}
