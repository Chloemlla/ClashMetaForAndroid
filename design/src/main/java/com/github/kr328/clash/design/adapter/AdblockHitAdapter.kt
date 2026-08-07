package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.AdblockHit
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.view.ActionLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdblockHitAdapter(
    private val context: Context,
) : RecyclerView.Adapter<AdblockHitAdapter.Holder>() {
    class Holder(val label: ActionLabel) : RecyclerView.ViewHolder(label)

    var hits: List<AdblockHit> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(ActionLabel(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        })
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = hits[position]

        holder.label.text = current.domain
        holder.label.subtext = formatHit(current)
    }

    override fun getItemCount(): Int {
        return hits.size
    }

    private fun formatHit(hit: AdblockHit): String {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(Date(hit.time))
        val source = when (hit.source) {
            "cfm-adblock" -> context.getString(R.string.adblock_source_cfm)
            "baidu" -> context.getString(R.string.adblock_source_baidu)
            else -> hit.source
        }

        return "$time · $source"
    }
}
