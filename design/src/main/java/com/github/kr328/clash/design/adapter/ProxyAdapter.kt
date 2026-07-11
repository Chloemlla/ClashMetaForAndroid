package com.github.kr328.clash.design.adapter

import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.component.ProxyView
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.component.ProxyViewState

class ProxyAdapter(
    private val config: ProxyViewConfig,
    private val clicked: (String) -> Unit,
) : RecyclerView.Adapter<ProxyAdapter.Holder>() {
    class Holder(val view: ProxyView) : RecyclerView.ViewHolder(view)

    var selectable: Boolean = false
    var states: List<ProxyViewState> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(ProxyView(config.context, config))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        bind(holder, position, snap = true)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            bind(holder, position, snap = false)
        }
    }

    private fun bind(holder: Holder, position: Int, snap: Boolean) {
        val current = states[position]

        holder.view.apply {
            state = current

            setOnClickListener {
                clicked(current.proxy.name)
            }

            val isSelector = this@ProxyAdapter.selectable

            selectable = isSelector
            isFocusable = isSelector
            isClickable = isSelector

            current.update(snap)
            isSelected = isSelector && current.isSelected
            contentDescription = context.getString(
                R.string.format_proxy_accessibility,
                current.title,
                current.subtitle,
                if (current.delayText.isEmpty()) {
                    context.getString(R.string.unavailable)
                } else {
                    context.getString(R.string.format_delay_milliseconds, current.delayText)
                },
            )
            ViewCompat.setStateDescription(
                this,
                if (isSelector) {
                    context.getString(
                        if (current.isSelected) R.string.selected else R.string.not_selected,
                    )
                } else {
                    null
                },
            )
            ViewCompat.setTooltipText(this, current.title)
            invalidate()
        }
    }

    override fun getItemCount(): Int {
        return states.size
    }

    companion object {
        const val PAYLOAD_PROXY = "proxy"
        const val PAYLOAD_SELECTION = "selection"
    }
}
