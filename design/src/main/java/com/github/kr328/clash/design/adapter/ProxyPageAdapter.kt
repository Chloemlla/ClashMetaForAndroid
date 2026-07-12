package com.github.kr328.clash.design.adapter

import android.view.ViewGroup
import androidx.databinding.Observable
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.BR
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.component.ProxyPageFactory
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.component.ProxyViewState
import com.github.kr328.clash.design.model.ProxyPageState
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.ui.Surface
import com.github.kr328.clash.design.util.diffWith
import com.github.kr328.clash.design.util.filterByKeyword
import com.github.kr328.clash.design.util.firstVisibleView
import com.github.kr328.clash.design.util.getPixels
import com.github.kr328.clash.design.util.indexOfSelected
import com.github.kr328.clash.design.util.addScrolledToBottomObserver
import com.github.kr328.clash.design.util.invalidateChildren
import com.github.kr328.clash.design.util.preserveOrderFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

class ProxyPageAdapter(
    private val surface: Surface,
    private val config: ProxyViewConfig,
    private val adapters: List<ProxyAdapter>,
    private val stateChanged: (Int) -> Unit,
) : RecyclerView.Adapter<ProxyPageFactory.Holder>() {
    private val factory = ProxyPageFactory(config)
    private var parent: RecyclerView? = null
    private val boundHolders = mutableMapOf<Int, WeakReference<ProxyPageFactory.Holder>>()
    private val boundRecyclerViews = mutableSetOf<RecyclerView>()
    private var extraTopInset = 0
    private var surfaceObserverInstalled = false

    private val allProxies = MutableList(adapters.size) { emptyList<Proxy>() }
    private val parents = MutableList(adapters.size) { ProxyState("") }
    private val linkMaps = MutableList(adapters.size) { emptyMap<String, ProxyState>() }
    private val keywords = MutableList(adapters.size) { "" }
    private val pendingScrollToSelected = BooleanArray(adapters.size)

    private val surfaceObserver = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (propertyId == BR.insets) {
                boundRecyclerViews.forEach { applyPadding(it) }
            }
        }
    }

    val states = List(adapters.size) { ProxyPageState() }

    suspend fun updateAdapter(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>,
        animateDelay: Boolean,
        preserveOrder: Boolean,
        scrollToSelected: Boolean = false,
    ) {
        val adapter = adapters[position]
        val previousAll = allProxies[position]
        val normalized = withContext(Dispatchers.Default) {
            if (preserveOrder && previousAll.isNotEmpty()) {
                proxies.preserveOrderFrom(previousAll) { it.name }
            } else {
                proxies
            }
        }

        allProxies[position] = normalized
        parents[position] = parent
        linkMaps[position] = links

        if (scrollToSelected) {
            pendingScrollToSelected[position] = true
        }

        applyDataset(
            position = position,
            adapter = adapter,
            selectable = selectable,
            animateDelay = animateDelay,
            preserveOrder = preserveOrder,
        )
    }

    suspend fun setKeyword(position: Int, keyword: String) {
        val normalized = keyword.trim()
        if (keywords[position] == normalized) return

        keywords[position] = normalized
        pendingScrollToSelected[position] = normalized.isEmpty()

        applyDataset(
            position = position,
            adapter = adapters[position],
            selectable = adapters[position].selectable,
            animateDelay = false,
            preserveOrder = true,
        )
    }

    fun keyword(position: Int): String = keywords[position]

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

    fun setExtraTopInset(extra: Int) {
        if (extraTopInset == extra) return
        extraTopInset = extra
        boundRecyclerViews.forEach { applyPadding(it) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProxyPageFactory.Holder {
        val holder = factory.newInstance()

        applyPadding(holder.recyclerView)
        boundRecyclerViews.add(holder.recyclerView)
        ensureSurfaceObserver()

        holder.recyclerView.addScrolledToBottomObserver { view, bottom ->
            val position = view.position
            val state = states.getOrNull(position) ?: return@addScrolledToBottomObserver

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
        boundHolders[position] = WeakReference(holder)

        holder.recyclerView.apply {
            this.position = position
            this.swapAdapter(adapter, false)
        }

        maybeScrollToSelected(holder.recyclerView, position)
    }

    override fun onViewRecycled(holder: ProxyPageFactory.Holder) {
        val position = holder.recyclerView.position
        if (position >= 0 && boundHolders[position]?.get() === holder) {
            boundHolders.remove(position)
        }
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = adapters.size

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.parent = recyclerView
        recyclerView.isFocusable = false
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.parent = null
        boundHolders.clear()
        boundRecyclerViews.clear()
        if (surfaceObserverInstalled) {
            surface.removeOnPropertyChangedCallback(surfaceObserver)
            surfaceObserverInstalled = false
        }
    }

    private fun ensureSurfaceObserver() {
        if (surfaceObserverInstalled) return
        surface.addOnPropertyChangedCallback(surfaceObserver)
        surfaceObserverInstalled = true
    }

    private fun contentTopOffset(): Int {
        val toolbarHeight = config.context.getPixels(R.dimen.toolbar_height)
        val tabHeight = config.context.getPixels(R.dimen.tab_layout_height)
        return toolbarHeight + tabHeight + extraTopInset
    }

    private fun applyPadding(recyclerView: RecyclerView) {
        val top = surface.insets.top + contentTopOffset()
        val bottom = surface.insets.bottom
        recyclerView.setPaddingRelative(0, top, 0, bottom)
    }

    private suspend fun applyDataset(
        position: Int,
        adapter: ProxyAdapter,
        selectable: Boolean,
        animateDelay: Boolean,
        preserveOrder: Boolean,
    ) {
        val oldStates = adapter.states
        val parentState = parents[position]
        val links = linkMaps[position]
        val keyword = keywords[position]
        val source = allProxies[position]

        val update = withContext(Dispatchers.Default) {
            val displayProxies = source.filterByKeyword(keyword)
            val oldProxies = oldStates.map { it.proxy }
            val sameStructure = oldProxies.size == displayProxies.size &&
                oldProxies.indices.all { index ->
                    oldProxies[index].name == displayProxies[index].name &&
                        oldProxies[index].isGroup == displayProxies[index].isGroup
                }

            AdapterUpdate(
                proxies = displayProxies,
                changedIndices = if (sameStructure) {
                    oldProxies.indices.filter { oldProxies[it] != displayProxies[it] }
                } else {
                    emptyList()
                },
                diff = if (sameStructure) {
                    null
                } else {
                    oldProxies.diffWith(
                        displayProxies,
                        detectMove = !preserveOrder,
                        id = { it.name },
                    )
                },
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
            } else {
                val oldStatesByName = oldStates.associateBy { it.proxy.name }
                val newStates = displayProxies.map { proxy ->
                    oldStatesByName[proxy.name]
                        ?.takeIf { it.proxy.isGroup == proxy.isGroup }
                        ?.apply { updateProxy(proxy, animateDelay) }
                        ?: ProxyViewState(
                            config,
                            proxy,
                            parentState,
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

            boundHolders[position]?.get()?.recyclerView?.let { recyclerView ->
                maybeScrollToSelected(recyclerView, position)
            }
        }
    }

    private fun maybeScrollToSelected(recyclerView: RecyclerView, position: Int) {
        if (!pendingScrollToSelected[position]) return

        val selectedName = parents[position].now
        val index = adapters[position].states.map { it.proxy }.indexOfSelected(selectedName)
        if (index < 0) {
            pendingScrollToSelected[position] = false
            return
        }

        pendingScrollToSelected[position] = false
        recyclerView.post {
            val layoutManager = recyclerView.layoutManager as? GridLayoutManager
            val offset = (recyclerView.height / 3).coerceAtLeast(0)
            layoutManager?.scrollToPositionWithOffset(index, offset)
                ?: recyclerView.scrollToPosition(index)
        }
    }

    private var RecyclerView.position: Int
        get() = tag as? Int ?: -1
        set(value) {
            tag = value
        }

    private data class AdapterUpdate(
        val proxies: List<Proxy>,
        val changedIndices: List<Int>,
        val diff: androidx.recyclerview.widget.DiffUtil.DiffResult?,
    )
}


