package com.github.kr328.clash.design.adapter

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.component.ProxyPageFactory
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.component.ProxyViewState
import com.github.kr328.clash.design.model.ProxyPageState
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.ui.Surface
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyPageAdapter(
    private val surface: Surface,
    private val config: ProxyViewConfig,
    private val adapters: List<ProxyAdapter>,
    private val stateChanged: (Int) -> Unit,
) : RecyclerView.Adapter<ProxyPageFactory.Holder>() {
    private val factory = ProxyPageFactory(config)
    private var parent: RecyclerView? = null

    val states = List(adapters.size) { ProxyPageState() }

    suspend fun updateAdapter(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>,
        animateDelay: Boolean,
        preserveOrder: Boolean,
    ) {
        val adapter = adapters[position]
        val oldStates = adapter.states
        val update = withContext(Dispatchers.Default) {
            val oldProxies = oldStates.map { it.proxy }
            val normalized = if (preserveOrder && oldProxies.isNotEmpty()) {
                proxies.preserveOrderFrom(oldProxies) { it.name }
            } else {
                proxies
            }

            val sameStructure = oldProxies.size == normalized.size &&
                oldProxies.indices.all { index ->
                    oldProxies[index].name == normalized[index].name &&
                        oldProxies[index].isGroup == normalized[index].isGroup
                }

            AdapterUpdate(
                proxies = normalized,
                changedIndices = if (sameStructure) {
                    oldProxies.indices.filter { oldProxies[it] != normalized[it] }
                } else {
                    emptyList()
                },
                diff = if (sameStructure) null else oldProxies.diffWith(
                    normalized,
                    detectMove = !preserveOrder,
                    id = { it.name },
                ),
            )
        }

        withContext(Dispatchers.Main) {
            val displayProxies = update.proxies
            val selectableChanged = adapter.selectable != selectable
            val diff = update.diff

            adapter.selectable = selectable

            if (diff == null) {
                update.changedIndices.forEach { index ->
                    oldStates[index].updateProxy(displayProxies[index], animateDelay)
                    adapter.notifyItemChanged(index, ProxyAdapter.PAYLOAD_PROXY)
                }

                if (selectableChanged && oldStates.isNotEmpty()) {
                    adapter.notifyItemRangeChanged(
                        0,
                        oldStates.size,
                        ProxyAdapter.PAYLOAD_SELECTION,
                    )
                }
                return@withContext
            }

            val oldStatesByName = oldStates.associateBy { it.proxy.name }
            val newStates = displayProxies.map { proxy ->
                oldStatesByName[proxy.name]
                    ?.takeIf { it.proxy.isGroup == proxy.isGroup }
                    ?.apply { updateProxy(proxy, animateDelay) }
                    ?: ProxyViewState(
                        config,
                        proxy,
                        parent,
                        if (proxy.isGroup) links[proxy.name] else null,
                    )
            }

            adapter.states = newStates
            diff.dispatchUpdatesTo(adapter)

            if (selectableChanged && oldStates.isNotEmpty() && newStates.isNotEmpty()) {
                adapter.notifyItemRangeChanged(
                    0,
                    newStates.size,
                    ProxyAdapter.PAYLOAD_SELECTION,
                )
            }
        }
    }

    fun notifySelectionChanged(position: Int) {
        val adapter = adapters[position]
        if (adapter.states.isNotEmpty()) {
            adapter.notifyItemRangeChanged(
                0,
                adapter.states.size,
                ProxyAdapter.PAYLOAD_SELECTION,
            )
        }
    }

    fun requestRedrawVisible() {
        factory.fromRoot(parent?.firstVisibleView ?: return)
            .recyclerView.invalidateChildren()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProxyPageFactory.Holder {
        val holder = factory.newInstance()

        val toolbarHeight = config.context.getPixels(R.dimen.toolbar_height)
        val tabHeight = config.context.getPixels(R.dimen.tab_layout_height)

        holder.recyclerView.bindInsets(surface, toolbarHeight + tabHeight)
        holder.recyclerView.addScrolledToBottomObserver { view, bottom ->
            val position = view.position
            val state = states[position]

            if (state.bottom != bottom) {
                state.bottom = bottom

                stateChanged(position)
            }
        }

        return holder
    }

    override fun onBindViewHolder(holder: ProxyPageFactory.Holder, position: Int) {
        val adapter = adapters[position]

        states[position].bottom = false

        holder.recyclerView.apply {
            this.position = position
            this.swapAdapter(adapter, false)
        }
    }

    override fun getItemCount(): Int {
        return adapters.size
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.parent = recyclerView

        recyclerView.isFocusable = false
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.parent = null
    }


    private var RecyclerView.position: Int
        get() {
            return tag as? Int ?: -1
        }
        set(value) {
            tag = value
        }

    private data class AdapterUpdate(
        val proxies: List<Proxy>,
        val changedIndices: List<Int>,
        val diff: androidx.recyclerview.widget.DiffUtil.DiffResult?,
    )
}
