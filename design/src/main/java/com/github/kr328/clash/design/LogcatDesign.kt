package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.adapter.LogMessageAdapter
import com.github.kr328.clash.design.databinding.DesignLogcatBinding
import com.github.kr328.clash.design.dialog.requestModelTextInput
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogcatDesign(
    context: Context,
    private val streaming: Boolean,
) : Design<LogcatDesign.Request>(context) {
    enum class Request {
        Close, Delete, Export
    }

    private val binding = DesignLogcatBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = LogMessageAdapter(context) {
        launch {
            val data = ClipData.newPlainText("log_message", it.message)

            context.getSystemService<ClipboardManager>()?.setPrimaryClip(data)

            showToast(R.string.copied, ToastDuration.Short)
        }
    }
    private var latestMessages: List<LogMessage> = emptyList()
    private var filterQuery = ""

    suspend fun patchMessages(messages: List<LogMessage>, removed: Int, appended: Int) {
        withContext(Dispatchers.Main) {
            latestMessages = messages
            renderMessages(removed, appended, allowIncremental = filterQuery.isEmpty())
        }
    }

    fun requestFilter() {
        launch {
            val updated = context.requestModelTextInput(
                initial = filterQuery.takeIf { it.isNotEmpty() },
                title = context.getString(R.string.filter_logs),
                reset = context.getString(R.string.reset),
                hint = context.getString(R.string.log_filter_hint),
            )
            withContext(Dispatchers.Main) {
                filterQuery = updated.orEmpty().trim()
                renderMessages(removed = 0, appended = 0, allowIncremental = false)
            }
        }
    }

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.streaming = streaming

        binding.activityBarLayout.applyFrom(context)

        binding.recyclerList.bindAppBarElevation(binding.activityBarLayout)

        binding.recyclerList.layoutManager = LinearLayoutManager(context).apply {
            if (streaming) {
                reverseLayout = true
                stackFromEnd = true
            }
        }
        binding.recyclerList.adapter = adapter
        binding.filterView.setOnClickListener { requestFilter() }
        updateFilterChrome()
    }

    private fun renderMessages(removed: Int, appended: Int, allowIncremental: Boolean) {
        val displayed = if (filterQuery.isEmpty()) {
            latestMessages
        } else {
            latestMessages.filter { it.matchesLogQuery(filterQuery) }
        }

        if (allowIncremental) {
            adapter.submitMessages(displayed, removed, appended)
        } else {
            adapter.replaceMessages(displayed)
        }
        binding.filterEmptyView.visibility =
            if (filterQuery.isNotEmpty() && displayed.isEmpty()) View.VISIBLE else View.GONE
        updateFilterChrome()

        if (streaming && displayed.isNotEmpty() && binding.recyclerList.isTop) {
            binding.recyclerList.scrollToPosition(displayed.size - 1)
        }
    }

    private fun updateFilterChrome() {
        binding.filterView.alpha = if (filterQuery.isEmpty()) 0.72f else 1f
        binding.filterView.contentDescription = if (filterQuery.isEmpty()) {
            context.getString(R.string.filter_logs)
        } else {
            context.getString(R.string.filter_logs_active, filterQuery)
        }
    }
}
