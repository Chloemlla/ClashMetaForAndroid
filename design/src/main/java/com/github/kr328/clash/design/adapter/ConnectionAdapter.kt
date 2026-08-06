package com.github.kr328.clash.design.adapter

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.View.AccessibilityDelegate
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterConnectionBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.toBytesString

class ConnectionAdapter(
    private val context: Context,
    private val onClick: (Connection) -> Unit,
    private val onCopy: (Connection) -> Unit,
) : ListAdapter<Connection, ConnectionAdapter.Holder>(DIFF) {

    class Holder(val binding: AdapterConnectionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterConnectionBinding.inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val conn = getItem(position)
        val host = if (conn.host.isNotEmpty()) conn.host else conn.dstIp
        holder.binding.hostView.text = "$host:${conn.dstPort}"
        holder.binding.networkView.text = "${conn.network}/${conn.type}"
        holder.binding.chainsView.text = conn.chains
        holder.binding.chainsView.visibility =
            if (conn.chains.isNotEmpty()) View.VISIBLE else View.GONE

        val process = when {
            conn.process.isNotEmpty() -> conn.process
            conn.packageName.isNotEmpty() -> conn.packageName
            else -> ""
        }
        holder.binding.processView.text = if (process.isNotEmpty() && conn.uid != 0) {
            "$process · uid=${conn.uid}"
        } else {
            process
        }
        holder.binding.processView.visibility =
            if (process.isNotEmpty()) View.VISIBLE else View.GONE

        val rule = buildString {
            append(conn.rule)
            if (conn.rulePayload.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(conn.rulePayload)
            }
        }
        holder.binding.ruleView.text = rule
        holder.binding.ruleView.visibility =
            if (rule.isNotEmpty()) View.VISIBLE else View.GONE

        holder.binding.trafficView.text =
            "↑${conn.upload.toBytesString()}  ↓${conn.download.toBytesString()}"

        val rowDescription = buildString {
            append(host).append(':').append(conn.dstPort)
            if (process.isNotEmpty()) {
                append(", ").append(process)
                if (conn.uid != 0) {
                    append(" (uid=").append(conn.uid).append(')')
                }
            }
            if (rule.isNotEmpty()) {
                append(", ").append(rule)
            }
            append(", ↑").append(conn.upload.toBytesString())
            append(" ↓").append(conn.download.toBytesString())
        }
        holder.binding.root.contentDescription = rowDescription

        holder.binding.root.setOnClickListener {
            onClick(conn)
        }
        holder.binding.root.setOnLongClickListener {
            onCopy(conn)
            true
        }

        // TalkBack exposes ACTION_CLICK (double-tap) for clickable rows but not the long-press
        // copy gesture, so surface an explicit accessibility action for it.
        val copyLabel = context.getString(R.string.connection_copy)
        holder.binding.root.accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_COPY_ID, copyLabel))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == ACTION_COPY_ID) {
                    onCopy(conn)
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        }
    }

    companion object {
        private const val ACTION_COPY_ID = 0x10001

        private val DIFF = object : DiffUtil.ItemCallback<Connection>() {
            override fun areItemsTheSame(old: Connection, new: Connection) = old.id == new.id
            override fun areContentsTheSame(old: Connection, new: Connection) = old == new
        }
    }
}
