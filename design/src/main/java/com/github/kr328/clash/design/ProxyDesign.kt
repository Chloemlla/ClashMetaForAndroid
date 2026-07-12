package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.widget.addTextChangedListener
import androidx.viewpager2.widget.ViewPager2
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.adapter.ProxyAdapter
import com.github.kr328.clash.design.adapter.ProxyPageAdapter
import com.github.kr328.clash.design.component.ProxyMenu
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.databinding.DesignProxyBinding
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.requestTextInput
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProxyDesign(
    context: Context,
    overrideMode: TunnelState.Mode?,
    groupNames: List<String>,
    uiStore: UiStore,
) : Design<ProxyDesign.Request>(context) {
    sealed class Request {
        object ReloadAll : Request()
        object ReLaunch : Request()

        data class PatchMode(val mode: TunnelState.Mode?) : Request()
        data class Reload(val index: Int) : Request()
        data class Select(val index: Int, val name: String) : Request()
        data class UrlTest(val index: Int) : Request()
    }

    private val binding = DesignProxyBinding
        .inflate(context.layoutInflater, context.root, false)

    private var config = ProxyViewConfig(context, uiStore.proxyLine)

    private val menu: ProxyMenu by lazy {
        ProxyMenu(context, binding.menuView, overrideMode, uiStore, requests) {
            config.proxyLine = uiStore.proxyLine
        }
    }

    private val adapter: ProxyPageAdapter
        get() = binding.pagesView.adapter!! as ProxyPageAdapter

    private var horizontalScrolling = false
    private val verticalBottomScrolled: Boolean
        get() = adapter.states[binding.pagesView.currentItem].bottom
    private var urlTesting: Boolean
        get() = adapter.states[binding.pagesView.currentItem].urlTesting
        set(value) {
            adapter.states[binding.pagesView.currentItem].urlTesting = value
        }

    private var searchFilterJob: Job? = null
    private val searchFilter = Channel<Unit>(Channel.CONFLATED)
    private var syncingKeyword = false

    override val root: View = binding.root

    suspend fun updateGroup(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>,
        animateDelay: Boolean = false,
        completeUrlTest: Boolean = true,
        preserveOrder: Boolean = false,
        selectionChanged: Boolean = false,
        scrollToSelected: Boolean = false,
    ) {
        adapter.updateAdapter(
            position,
            proxies,
            selectable,
            parent,
            links,
            animateDelay,
            preserveOrder,
            scrollToSelected,
        )

        if (selectionChanged) {
            adapter.notifySelectionChanged(position)
        }

        if (completeUrlTest) {
            adapter.states[position].urlTesting = false
        }

        updateUrlTestButtonStatus()
    }

    suspend fun notifySelectionChanged(position: Int) {
        withContext(Dispatchers.Main) {
            adapter.notifySelectionChanged(position)
        }
    }

    suspend fun showModeSwitchTips() {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.mode_switch_tips, Toast.LENGTH_LONG).show()
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.menuView.setOnClickListener {
            menu.show()
        }

        if (groupNames.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE

            binding.urlTestView.visibility = View.GONE
            binding.searchView.visibility = View.GONE
            binding.searchBar.visibility = View.GONE
            binding.tabLayoutView.visibility = View.GONE
            binding.elevationView.visibility = View.GONE
            binding.pagesView.visibility = View.GONE
            binding.urlTestFloatView.visibility = View.GONE
        } else {
            binding.urlTestFloatView.supportImageTintList = ColorStateList.valueOf(
                context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)
            )

            binding.searchView.setOnClickListener {
                toggleSearchBar(show = binding.searchBar.visibility != View.VISIBLE)
            }

            binding.clearSearchView.setOnClickListener {
                if (binding.keywordView.text.isNullOrEmpty()) {
                    toggleSearchBar(show = false)
                } else {
                    binding.keywordView.setText("")
                }
            }

            binding.keywordView.addTextChangedListener {
                if (!syncingKeyword) {
                    searchFilter.trySend(Unit)
                }
            }
            binding.keywordView.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard()
                    true
                } else {
                    false
                }
            }

            searchFilterJob = launch {
                while (isActive) {
                    searchFilter.receive()
                    delay(150)

                    if (groupNamesEmpty()) continue

                    val page = binding.pagesView.currentItem
                    val keyword = binding.keywordView.text?.toString().orEmpty()
                    adapter.setKeyword(page, keyword)
                    updateSearchButtonStatus()
                }
            }

            binding.pagesView.apply {
                adapter = ProxyPageAdapter(
                    surface,
                    config,
                    List(groupNames.size) { index ->
                        ProxyAdapter(config) { name ->
                            requests.trySend(Request.Select(index, name))
                        }
                    }
                ) {
                    if (it == currentItem)
                        updateUrlTestButtonStatus()
                }

                registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageScrollStateChanged(state: Int) {
                        horizontalScrolling = state != ViewPager2.SCROLL_STATE_IDLE

                        updateUrlTestButtonStatus()
                    }

                    override fun onPageSelected(position: Int) {
                        uiStore.proxyLastGroup = groupNames[position]
                        syncKeywordField()
                        updateSearchButtonStatus()
                        updateUrlTestButtonStatus()
                    }
                })
            }

            TabLayoutMediator(binding.tabLayoutView, binding.pagesView) { tab, index ->
                tab.text = groupNames[index]
            }.attach()

            val initialPosition = groupNames.indexOf(uiStore.proxyLastGroup)

            binding.pagesView.post {
                if (initialPosition > 0)
                    binding.pagesView.setCurrentItem(initialPosition, false)
                syncKeywordField()
                updateSearchButtonStatus()
            }
        }
    }

    fun requestUrlTesting() {
        if (groupNamesEmpty() || urlTesting) return

        urlTesting = true

        requests.trySend(Request.UrlTest(binding.pagesView.currentItem))

        updateUrlTestButtonStatus()
    }

    private fun toggleSearchBar(show: Boolean) {
        if (groupNamesEmpty()) return

        binding.searchBar.visibility = if (show) View.VISIBLE else View.GONE
        updateListTopInset()

        if (show) {
            binding.keywordView.requestTextInput()
        } else {
            hideKeyboard()
            if (!binding.keywordView.text.isNullOrEmpty()) {
                binding.keywordView.setText("")
            } else {
                updateSearchButtonStatus()
            }
        }
    }

    private fun syncKeywordField() {
        if (groupNamesEmpty()) return

        val keyword = adapter.keyword(binding.pagesView.currentItem)
        syncingKeyword = true
        try {
            if (binding.keywordView.text?.toString() != keyword) {
                binding.keywordView.setText(keyword)
                binding.keywordView.setSelection(keyword.length)
            }
            if (keyword.isNotEmpty()) {
                binding.searchBar.visibility = View.VISIBLE
            }
            updateListTopInset()
        } finally {
            syncingKeyword = false
        }
    }

    private fun updateUrlTestButtonStatus() {
        if (groupNamesEmpty()) return

        if (verticalBottomScrolled || horizontalScrolling || urlTesting) {
            binding.urlTestFloatView.hide()
        } else {
            binding.urlTestFloatView.show()
        }

        if (urlTesting) {
            binding.urlTestView.visibility = View.GONE
            binding.urlTestProgressView.visibility = View.VISIBLE
            binding.urlTestView.isEnabled = false
            binding.urlTestFloatView.isEnabled = false
        } else {
            binding.urlTestView.visibility = View.VISIBLE
            binding.urlTestProgressView.visibility = View.GONE
            binding.urlTestView.isEnabled = true
            binding.urlTestFloatView.isEnabled = true
        }
    }

    private fun updateSearchButtonStatus() {
        if (groupNamesEmpty()) return

        val hasKeyword = adapter.keyword(binding.pagesView.currentItem).isNotEmpty()
        binding.searchView.alpha = if (hasKeyword || binding.searchBar.visibility == View.VISIBLE) 1f else 0.85f
        binding.searchView.isSelected = hasKeyword
    }

    private fun groupNamesEmpty(): Boolean {
        return binding.pagesView.adapter == null
    }

    
    private fun updateListTopInset() {
        if (groupNamesEmpty()) return
        val extra = if (binding.searchBar.visibility == View.VISIBLE) {
            context.resources.getDimensionPixelSize(R.dimen.proxy_search_bar_height)
        } else {
            0
        }
        adapter.setExtraTopInset(extra)
    }
    private fun hideKeyboard() {
        val imm = context.getSystemService<InputMethodManager>() ?: return
        imm.hideSoftInputFromWindow(binding.keywordView.windowToken, 0)
        binding.keywordView.clearFocus()
    }
}


