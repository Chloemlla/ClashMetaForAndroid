package com.github.kr328.clash.design.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Master-list adapter for the large-screen Proxy list-detail layout.
 *
 * This is a pure navigation aid over the existing [ProxyPageAdapter] / `ViewPager2` pages —
 * clicking a row only moves the ViewPager2's current item, it does not duplicate any of the
 * proxy business logic (search / sort / progressive delay / selection all stay owned by
 * [ProxyPageAdapter] and `ProxyAdapter`).
 */
class ProxyGroupListAdapter(
    private val groupNames: List<String>,
    private val onGroupClicked: (index: Int) -> Unit,
) : RecyclerView.Adapter<ProxyGroupListAdapter.Holder>() {
    class Holder(
        val text: TextView,
        onGroupClicked: (index: Int) -> Unit,
    ) : RecyclerView.ViewHolder(text) {
        init {
            text.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onGroupClicked(position)
                }
            }
        }
    }

    private var selectedIndex: Int = -1

    fun setSelected(index: Int) {
        if (selectedIndex == index) return

        val previous = selectedIndex
        selectedIndex = index

        if (previous in groupNames.indices) notifyItemChanged(previous)
        if (index in groupNames.indices) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(
            android.R.layout.simple_list_item_activated_1,
            parent,
            false,
        ) as TextView

        return Holder(view, onGroupClicked)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val selected = position == selectedIndex
        holder.text.text = groupNames[position]
        holder.text.isActivated = selected
        holder.text.isSelected = selected
    }

    override fun getItemCount(): Int = groupNames.size
}
