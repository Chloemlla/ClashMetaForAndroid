package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.adapter.LogFileAdapter
import com.github.kr328.clash.design.databinding.DesignLogsBinding
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.design.svg.UndrawIllustration
import com.github.kr328.clash.design.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class LogsDesign(context: Context) : Design<LogsDesign.Request>(context) {
    sealed class Request {
        object StartLogcat : Request()
        object DeleteAll : Request()

        data class OpenFile(val file: LogFile) : Request()
    }

    private val binding = DesignLogsBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = LogFileAdapter(context) {
        requests.trySend(Request.OpenFile(it))
    }

    override val root: View
        get() = binding.root

    suspend fun patchLogs(logs: List<LogFile>) {
        adapter.patchDataSet(adapter::logs, logs, false, LogFile::fileName)

        withContext(Dispatchers.Main) {
            val empty = logs.isEmpty()
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.historyTitle.visibility = if (empty) View.GONE else View.VISIBLE
            binding.recyclerList.visibility = if (empty) View.GONE else View.VISIBLE
            binding.deleteView.isEnabled = !empty
            binding.deleteView.isClickable = !empty
            binding.deleteView.alpha = if (empty) 0.4f else 1f
        }
    }

    suspend fun requestDeleteAll(): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.delete_all_logs)
                    .setMessage(R.string.delete_all_logs_warn)
                    .setPositiveButton(R.string.ok) { _, _ -> ctx.resume(true) }
                    .setNegativeButton(R.string.cancel) { _, _ -> }
                    .show()
                    .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(false) }
            }
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.emptyIllustration.illustration = UndrawIllustration.Coder

        binding.recyclerList.applyLinearAdapter(context, adapter)
    }
}
