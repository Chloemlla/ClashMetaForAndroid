package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.design.adapter.ConnectionAdapter
import com.github.kr328.clash.design.databinding.DesignConnectionsBinding
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ConnectionsDesign(context: Context) : Design<ConnectionsDesign.Request>(context) {

    sealed class Request {
        object CloseAll : Request()
        data class Close(val id: String) : Request()
    }

    private val binding = DesignConnectionsBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = ConnectionAdapter(
        context,
        onClick = { conn ->
            requests.trySend(Request.Close(conn.id))
        },
        onCopy = { conn ->
            launch {
                val text = buildString {
                    append(if (conn.host.isNotEmpty()) conn.host else conn.dstIp)
                    append(":")
                    append(conn.dstPort)
                    append("  ")
                    append(conn.network)
                    append("/")
                    append(conn.type)
                    if (conn.process.isNotEmpty()) {
                        append("\n")
                        append(conn.process)
                        if (conn.uid != 0) {
                            append(" (uid=")
                            append(conn.uid)
                            append(")")
                        }
                    }
                    if (conn.chains.isNotEmpty()) {
                        append("\n")
                        append(conn.chains)
                    }
                    if (conn.rule.isNotEmpty()) {
                        append("\n")
                        append(conn.rule)
                        if (conn.rulePayload.isNotEmpty()) {
                            append(" · ")
                            append(conn.rulePayload)
                        }
                    }
                    append("\n↑")
                    append(conn.upload.toBytesString())
                    append("  ↓")
                    append(conn.download.toBytesString())
                }
                context.getSystemService<ClipboardManager>()
                    ?.setPrimaryClip(ClipData.newPlainText("connection", text))
                showToast(R.string.copied, ToastDuration.Short)
            }
        },
    )

    suspend fun updateConnections(connections: List<Connection>) {
        withContext(Dispatchers.Main) {
            adapter.submitList(connections)
            val count = connections.size
            binding.emptyView.visibility = if (count == 0) View.VISIBLE else View.GONE
            binding.closeAllView.isEnabled = count > 0
            binding.closeAllView.isClickable = count > 0
            binding.closeAllView.alpha = if (count == 0) 0.4f else 1f
            binding.activityBarLayout
                .findViewById<TextView>(R.id.activity_bar_title_view)
                ?.text = if (count == 0) {
                context.getString(R.string.connections)
            } else {
                context.getString(R.string.format_connections_title, count)
            }
        }
    }

    suspend fun requestCloseAll(): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.close_all_connections)
                    .setMessage(R.string.close_all_connections_warn)
                    .setPositiveButton(R.string.ok) { _, _ -> ctx.resume(true) }
                    .setNegativeButton(R.string.cancel) { _, _ -> }
                    .show()
                    .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(false) }
            }
        }
    }

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.activityBarLayout.applyFrom(context)
        binding.recyclerList.bindAppBarElevation(binding.activityBarLayout)
        binding.recyclerList.layoutManager = LinearLayoutManager(context)
        binding.recyclerList.adapter = adapter
        binding.emptyView.visibility = View.VISIBLE
        binding.closeAllView.isEnabled = false
        binding.closeAllView.isClickable = false
        binding.closeAllView.alpha = 0.4f
    }
}
