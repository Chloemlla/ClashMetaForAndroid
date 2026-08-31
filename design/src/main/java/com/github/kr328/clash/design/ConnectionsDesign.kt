package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.design.adapter.ConnectionAggregateAdapter
import com.github.kr328.clash.design.adapter.ConnectionAdapter
import com.github.kr328.clash.design.component.ConnectionAppLabelCache
import com.github.kr328.clash.design.component.ConnectionSortMenu
import com.github.kr328.clash.design.databinding.DesignConnectionsBinding
import com.github.kr328.clash.design.dialog.requestModelTextInput
import com.github.kr328.clash.design.svg.UndrawIllustration
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.ConnectionAggregate
import com.github.kr328.clash.design.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ConnectionsDesign(context: Context) : Design<ConnectionsDesign.Request>(context) {

    private enum class ViewMode(
        val grouping: ConnectionGrouping?,
        @StringRes val labelRes: Int,
    ) {
        Live(null, R.string.connection_view_live),
        App(ConnectionGrouping.App, R.string.connection_view_app),
        Host(ConnectionGrouping.Host, R.string.connection_view_host),
        Chain(ConnectionGrouping.Chain, R.string.connection_view_chain),
    }

    sealed class Request {
        object CloseAll : Request()
        data class Close(val id: String) : Request()
    }

    private val binding = DesignConnectionsBinding
        .inflate(context.layoutInflater, context.root, false)

    private val rawAdapter = ConnectionAdapter(
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
    private val aggregateAdapter = ConnectionAggregateAdapter(context)
    private val appLabelCache = ConnectionAppLabelCache(context)
    private val sortMenu by lazy {
        ConnectionSortMenu(
            context = context,
            anchor = binding.sortView,
            initial = aggregateSort,
            onSortChanged = { sort ->
                aggregateSort = sort
                renderConnections(resetScroll = true)
            },
        )
    }

    private var connections: List<Connection> = emptyList()
    private var appLabels: Map<String, String> = emptyMap()
    private var viewMode = ViewMode.Live
    private var aggregateSort = ConnectionAggregateSort.Bytes
    private var keyword = ""

    // Generation counter so a stale background render (superseded by a newer one) is dropped.
    private var renderGeneration = 0L

    private sealed class FilteredConnections {
        data class Raw(val items: List<Connection>) : FilteredConnections()
        data class Aggregates(val items: List<ConnectionAggregate>) : FilteredConnections()
    }

    suspend fun updateConnections(connections: List<Connection>) {
        val labels = appLabelCache.resolve(connections)
        withContext(Dispatchers.Main) {
            this@ConnectionsDesign.connections = connections
            appLabels = labels
            renderConnections(resetScroll = false)
        }
    }

    suspend fun requestCloseAll(): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.close_all_connections)
                    .setMessage(R.string.close_all_connections_warn)
                    .setPositiveButton(R.string.ok) { _, _ -> ctx.resume(true) }
                    .setNegativeButton(R.string.cancel) { _, _ -> }
                    .show()

                dialog.setOnDismissListener { if (!ctx.isCompleted) ctx.resume(false) }

                // The hosting activity may cancel the coroutine while the dialog is up
                // (e.g. back press); dismiss the window so it does not leak.
                ctx.invokeOnCancellation {
                    dialog.dismiss()
                }
            }
        }
    }

    override val root: View
        get() = binding.root

    fun requestSearch() {
        launch {
            val updated = context.requestModelTextInput(
                initial = keyword.takeIf { it.isNotEmpty() },
                title = context.getString(R.string.search_connections),
                reset = context.getString(R.string.reset),
                hint = context.getString(R.string.connection_search_hint),
            )
            withContext(Dispatchers.Main) {
                keyword = updated.orEmpty().trim()
                renderConnections(resetScroll = true)
            }
        }
    }

    init {
        binding.self = this
        binding.activityBarLayout.applyFrom(context)
        binding.recyclerList.bindAppBarElevation(binding.activityBarLayout)
        binding.recyclerList.layoutManager = LinearLayoutManager(context)
        binding.recyclerList.adapter = rawAdapter
        binding.emptyIllustration.illustration = UndrawIllustration.VideoStreaming
        binding.searchView.setOnClickListener { requestSearch() }
        binding.sortView.setOnClickListener { sortMenu.show() }
        setupTabs()
        renderConnections(resetScroll = false)
    }

    private fun setupTabs() {
        // Each tab carries its own ViewMode via tag, decoupling tab order from enum order.
        ViewMode.values().forEach { mode ->
            binding.tabLayoutView.addTab(
                binding.tabLayoutView.newTab()
                    .setText(mode.labelRes)
                    .apply { tag = mode }
            )
        }
        binding.tabLayoutView.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                viewMode = tab.tag as? ViewMode ?: ViewMode.Live
                renderConnections(resetScroll = true)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
                binding.recyclerList.scrollToPosition(0)
            }
        })
    }

    private fun renderConnections(resetScroll: Boolean) {
        // Snapshot the inputs on the main thread, then aggregate/filter off-thread.
        // A newer render bumps the generation so an in-flight stale result is discarded (conflate).
        val generation = ++renderGeneration
        val snapshotConnections = connections
        val snapshotLabels = appLabels
        val snapshotKeyword = keyword
        val snapshotMode = viewMode
        val snapshotSort = aggregateSort

        launch {
            val filtered = withContext(Dispatchers.Default) {
                when (val grouping = snapshotMode.grouping) {
                    null -> FilteredConnections.Raw(
                        filterConnections(snapshotConnections, snapshotKeyword)
                    )
                    else -> FilteredConnections.Aggregates(
                        filterConnectionAggregates(
                            aggregateConnections(
                                snapshotConnections,
                                grouping,
                                snapshotSort,
                                snapshotLabels,
                            ),
                            snapshotKeyword,
                        )
                    )
                }
            }

            if (generation != renderGeneration || !isActive) return@launch

            val visibleCount = when (filtered) {
                is FilteredConnections.Raw -> {
                    if (binding.recyclerList.adapter !== rawAdapter) {
                        binding.recyclerList.adapter = rawAdapter
                    }
                    rawAdapter.submitList(filtered.items)
                    filtered.items.size
                }
                is FilteredConnections.Aggregates -> {
                    if (binding.recyclerList.adapter !== aggregateAdapter) {
                        binding.recyclerList.adapter = aggregateAdapter
                    }
                    aggregateAdapter.submitList(filtered.items)
                    filtered.items.size
                }
            }

            val hasConnections = snapshotConnections.isNotEmpty()
            binding.emptyView.visibility = if (visibleCount == 0) View.VISIBLE else View.GONE
            binding.emptyTextView.setText(
                if (hasConnections) R.string.connections_filter_empty else R.string.connections_empty,
            )
            binding.closeAllView.isEnabled = hasConnections
            binding.closeAllView.isClickable = hasConnections
            binding.closeAllView.alpha = if (hasConnections) 1f else 0.4f
            binding.sortView.isEnabled = snapshotMode != ViewMode.Live
            binding.sortView.isClickable = snapshotMode != ViewMode.Live
            binding.sortView.alpha = if (snapshotMode == ViewMode.Live) 0.4f else 1f
            binding.searchView.alpha = if (snapshotKeyword.isEmpty()) 0.72f else 1f
            binding.searchView.contentDescription = if (snapshotKeyword.isEmpty()) {
                context.getString(R.string.search_connections)
            } else {
                context.getString(R.string.search_connections_active, snapshotKeyword)
            }
            binding.activityBarLayout
                .findViewById<TextView>(R.id.activity_bar_title_view)
                ?.text = if (snapshotConnections.isEmpty()) {
                context.getString(R.string.connections)
            } else {
                context.getString(R.string.format_connections_title, snapshotConnections.size)
            }

            if (resetScroll && visibleCount > 0) {
                binding.recyclerList.scrollToPosition(0)
            }
        }
    }
}
