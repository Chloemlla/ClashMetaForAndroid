package com.github.kr328.clash.design.adapter

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.common.compat.foreground
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterPartnerAppBinding
import com.github.kr328.clash.design.model.PartnerAppInfo
import com.github.kr328.clash.design.model.PartnerAuthorization
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
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

class PartnerAppAdapter(
    private val context: Context,
    private val onClick: (PartnerAppInfo) -> Unit,
) : RecyclerView.Adapter<PartnerAppAdapter.Holder>() {
    class Holder(val binding: AdapterPartnerAppBinding) : RecyclerView.ViewHolder(binding.root) {
        val defaultStatusColor: Int = binding.statusView.currentTextColor
        var boundPackage: String? = null
        var iconJob: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val iconLoads = Semaphore(AppAdapter.ICON_LOAD_CONCURRENCY)
    private val iconCache = object : LruCache<String, Drawable>(AppAdapter.ICON_CACHE_ENTRIES) {}
    private val allowedColor =
        context.resolveThemedColor(androidx.appcompat.R.attr.colorPrimary)
    private val deniedColor =
        context.resolveThemedColor(androidx.appcompat.R.attr.colorError)

    var apps: List<PartnerAppInfo> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(AdapterPartnerAppBinding.inflate(context.layoutInflater, context.root, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = apps[position]

        holder.iconJob?.cancel()
        holder.boundPackage = current.packageName
        holder.binding.app = current
        holder.binding.statusText = statusOf(current)
        holder.binding.digestText = digestOf(current)
        holder.binding.statusView.setTextColor(
            when (current.authorization) {
                PartnerAuthorization.Allowed -> allowedColor
                PartnerAuthorization.Denied -> deniedColor
                else -> holder.defaultStatusColor
            }
        )
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
        holder.binding.root.setOnClickListener { onClick(current) }
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

    override fun getItemCount(): Int {
        return apps.size
    }

    private fun statusOf(app: PartnerAppInfo): String {
        val tunneled = context.getString(
            if (app.tunneled) R.string.partner_status_tunneled else R.string.partner_status_not_tunneled
        )
        val authorization = context.getString(
            when (app.authorization) {
                PartnerAuthorization.Allowed -> R.string.partner_status_allowed
                PartnerAuthorization.Denied -> R.string.partner_status_denied
                PartnerAuthorization.Pending -> R.string.partner_status_pending
                PartnerAuthorization.Undecided -> R.string.partner_status_undecided
            }
        )

        return "$tunneled · $authorization"
    }

    private fun digestOf(app: PartnerAppInfo): String {
        val signer = context.getString(
            if (app.signerVerified) R.string.partner_signer_verified else R.string.partner_signer_unverified
        )
        val digest = app.certificateSha256?.take(DIGEST_PREVIEW_LENGTH) ?: return signer

        return context.getString(R.string.format_partner_certificate, digest) + " · " + signer
    }

    private fun cachedIcon(packageName: String): Drawable? {
        return iconCache.get(packageName)?.let(::copyDrawable)
    }

    private fun copyDrawable(drawable: Drawable): Drawable {
        return drawable.constantState?.newDrawable(context.resources)?.mutate() ?: drawable
    }

    companion object {
        private const val DIGEST_PREVIEW_LENGTH = 16
    }
}
