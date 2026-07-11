package com.github.kr328.clash.design.adapter

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.common.compat.foreground
import com.github.kr328.clash.design.databinding.AdapterAppBinding
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class AppAdapter(
    private val context: Context,
    private val selected: MutableSet<String>,
) : RecyclerView.Adapter<AppAdapter.Holder>() {
    class Holder(val binding: AdapterAppBinding) : RecyclerView.ViewHolder(binding.root) {
        var boundPackage: String? = null
        var iconJob: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val iconLoads = Semaphore(ICON_LOAD_CONCURRENCY)
    private val iconCache = object : LruCache<String, Drawable>(ICON_CACHE_ENTRIES) {}

    var apps: List<AppInfo> = emptyList()

    fun rebindAll() {
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterAppBinding
                .inflate(context.layoutInflater, context.root, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = apps[position]

        holder.iconJob?.cancel()
        holder.boundPackage = current.packageName
        holder.binding.app = current
        holder.binding.selected = current.packageName in selected
        holder.binding.iconView.background = cachedIcon(current.packageName)
        if (holder.binding.iconView.background == null) {
            holder.iconJob = scope.launch {
                val icon = iconLoads.withPermit {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.packageManager
                                .getApplicationIcon(current.packageName)
                                .foreground()
                        }.getOrNull()
                    }
                }

                if (icon != null) {
                    iconCache.put(current.packageName, icon)
                    if (holder.boundPackage == current.packageName) {
                        holder.binding.iconView.background = copyDrawable(icon)
                    }
                }
            }
        }
        holder.binding.root.setOnClickListener {
            if (holder.binding.selected) {
                selected.remove(current.packageName)
                holder.binding.selected = false
            } else {
                selected.add(current.packageName)
                holder.binding.selected = true
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.iconJob?.cancel()
        holder.iconJob = null
        holder.boundPackage = null
        holder.binding.iconView.background = null
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        scope.coroutineContext.cancelChildren()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun cachedIcon(packageName: String): Drawable? {
        return iconCache.get(packageName)?.let(::copyDrawable)
    }

    private fun copyDrawable(drawable: Drawable): Drawable {
        return drawable.constantState?.newDrawable(context.resources)?.mutate() ?: drawable
    }

    override fun getItemCount(): Int {
        return apps.size
    }

    companion object {
        const val ICON_CACHE_ENTRIES = 48
        const val ICON_LOAD_CONCURRENCY = 2
    }
}
