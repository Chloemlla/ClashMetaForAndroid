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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectionsDesign(context: Context) : Design<Unit>(context) {

    private val binding = DesignConnectionsBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = ConnectionAdapter(context) { conn ->
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
    }

    suspend fun updateConnections(connections: List<Connection>) {
        withContext(Dispatchers.Main) {
            adapter.submitList(connections)
            val count = connections.size
            binding.emptyView.visibility = if (count == 0) View.VISIBLE else View.GONE
            binding.activityBarLayout
                .findViewById<TextView>(R.id.activity_bar_title_view)
                ?.text = if (count == 0) {
                context.getString(R.string.connections)
            } else {
                context.getString(R.string.format_connections_title, count)
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
    }
}