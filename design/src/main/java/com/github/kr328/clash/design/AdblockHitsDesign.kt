package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.core.model.AdblockHit
import com.github.kr328.clash.core.model.AdblockStats
import com.github.kr328.clash.design.adapter.AdblockHitAdapter
import com.github.kr328.clash.design.databinding.DesignAdblockHitsBinding
import com.github.kr328.clash.design.svg.UndrawIllustration
import com.github.kr328.clash.design.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class AdblockHitsDesign(context: Context) : Design<AdblockHitsDesign.Request>(context) {
    enum class ClearTarget { Hits, Logs, All }

    sealed class Request {
        object Clear : Request()
    }

    private val binding = DesignAdblockHitsBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = AdblockHitAdapter(context)
    private val hits = mutableListOf<AdblockHit>()

    override val root: View
        get() = binding.root

    suspend fun setStats(stats: AdblockStats) {
        withContext(Dispatchers.Main) {
            val rate = if (stats.total > 0) (stats.blocked * 100 / stats.total).toInt() else 0
            val top = stats.topDomains
                .take(5)
                .joinToString(", ") { "${it.domain} (${it.count})" }

            binding.statsTotal.text =
                context.getString(R.string.adblock_stats_total) + ": " + stats.total
            binding.statsBlocked.text =
                context.getString(R.string.adblock_stats_blocked) + ": " + stats.blocked
            binding.statsRate.text =
                context.getString(R.string.adblock_stats_rate) + ": " + rate + "%"
            binding.statsTop.text =
                context.getString(R.string.adblock_stats_top_domains) + ": " + top
        }
    }

    suspend fun patchHits(newHits: List<AdblockHit>) {
        hits.clear()
        hits.addAll(newHits)

        submitHits()
    }

    suspend fun appendHit(hit: AdblockHit) {
        // Cold-start dedupe: on the first frame the observer is registered before the
        // ActivityStart history load, so a hit that arrives in that window is both already
        // in the JSONL read by patchHits() and queued on the live channel. Skip it to avoid
        // a duplicate row. Keyed on time+domain+network (unique per recorder record).
        if (hits.any { it.time == hit.time && it.domain == hit.domain && it.network == hit.network }) {
            return
        }

        hits.add(hit)

        submitHits()
    }

    suspend fun requestClearTarget(): ClearTarget? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                var chosen = -1
                val options = arrayOf(
                    context.getString(R.string.adblock_clear_hits),
                    context.getString(R.string.adblock_clear_logs),
                    context.getString(R.string.adblock_clear_all),
                )

                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.adblock_clear_title)
                    .setMessage(R.string.adblock_clear_message)
                    .setSingleChoiceItems(options, -1) { _, which -> chosen = which }
                    .setPositiveButton(R.string.ok) { _, _ ->
                        ctx.resume(ClearTarget.values().getOrNull(chosen))
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> ctx.resume(null) }
                    .show()
                    .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(null) }
            }
        }
    }

    fun clearRecords() {
        hits.clear()

        launch {
            submitHits()

            withContext(Dispatchers.Main) {
                binding.statsTotal.text = ""
                binding.statsBlocked.text = ""
                binding.statsRate.text = ""
                binding.statsTop.text = ""
            }
        }
    }

    private suspend fun submitHits() {
        val snapshot = hits.toList()

        adapter.patchDataSet(adapter::hits, snapshot, false, AdblockHit::domain)

        withContext(Dispatchers.Main) {
            val empty = snapshot.isEmpty()

            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.recyclerList.visibility = if (empty) View.GONE else View.VISIBLE
            binding.deleteView.isEnabled = !empty
            binding.deleteView.isClickable = !empty
            binding.deleteView.alpha = if (empty) 0.4f else 1f
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.emptyIllustration.illustration = UndrawIllustration.Coder

        binding.recyclerList.applyLinearAdapter(context, adapter)
    }
}
