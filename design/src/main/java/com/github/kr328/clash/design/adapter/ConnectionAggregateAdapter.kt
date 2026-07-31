package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterConnectionAggregateBinding
import com.github.kr328.clash.design.util.ConnectionAggregate
import com.github.kr328.clash.design.util.ConnectionGrouping
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.toBytesString

class ConnectionAggregateAdapter(
    private val context: Context,
) : ListAdapter<ConnectionAggregate, ConnectionAggregateAdapter.Holder>(DIFF) {
    class Holder(val binding: AdapterConnectionAggregateBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterConnectionAggregateBinding.inflate(context.layoutInflater, parent, false),
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val aggregate = getItem(position)
        holder.binding.titleView.text = aggregate.title.ifEmpty {
            context.getString(
                when (aggregate.grouping) {
                    ConnectionGrouping.App -> R.string.connection_unknown_app
                    ConnectionGrouping.Host -> R.string.connection_unknown_host
                    ConnectionGrouping.Chain -> R.string.connection_direct_chain
                },
            )
        }
        holder.binding.subtitleView.text = aggregate.subtitle
        holder.binding.subtitleView.visibility =
            if (aggregate.subtitle.isEmpty()) View.GONE else View.VISIBLE
        holder.binding.countView.text = context.resources.getQuantityString(
            R.plurals.connection_count,
            aggregate.count,
            aggregate.count,
        )
        holder.binding.trafficView.text = context.getString(
            R.string.format_connection_traffic,
            aggregate.upload.toBytesString(),
            aggregate.download.toBytesString(),
        )
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ConnectionAggregate>() {
            override fun areItemsTheSame(old: ConnectionAggregate, new: ConnectionAggregate): Boolean {
                return old.grouping == new.grouping && old.key == new.key
            }

            override fun areContentsTheSame(old: ConnectionAggregate, new: ConnectionAggregate): Boolean {
                return old == new
            }
        }
    }
}
