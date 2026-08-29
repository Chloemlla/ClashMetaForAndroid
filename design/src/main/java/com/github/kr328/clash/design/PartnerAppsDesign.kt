package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.adapter.PartnerAppAdapter
import com.github.kr328.clash.design.databinding.DesignPartnerAppsBinding
import com.github.kr328.clash.design.model.PartnerAppInfo
import com.github.kr328.clash.design.model.PartnerAuthorization
import com.github.kr328.clash.design.svg.UndrawIllustration
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.patchDataSet
import com.github.kr328.clash.design.util.root
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PartnerAppsDesign(context: Context) : Design<PartnerAppsDesign.Request>(context) {
    sealed class Request {
        data class Allow(val packageName: String) : Request()
        data class Deny(val packageName: String) : Request()
        data class Revoke(val packageName: String) : Request()
    }

    private val binding = DesignPartnerAppsBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = PartnerAppAdapter(context, ::showActions)

    override val root: View
        get() = binding.root

    suspend fun patchApps(apps: List<PartnerAppInfo>) {
        adapter.patchDataSet(adapter::apps, apps, false, PartnerAppInfo::packageName)

        withContext(Dispatchers.Main) {
            val empty = apps.isEmpty()

            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.recyclerList.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    suspend fun setTunnelSummary(running: Boolean, tunneledCount: Int) {
        withContext(Dispatchers.Main) {
            binding.summaryView.text = if (running) {
                context.getString(R.string.format_partner_tunneled, tunneledCount)
            } else {
                context.getString(R.string.partner_tunnel_stopped)
            }
        }
    }

    private fun showActions(app: PartnerAppInfo) {
        val actions = when (app.authorization) {
            PartnerAuthorization.Allowed ->
                listOf(R.string.partner_action_revoke to Request.Revoke(app.packageName))
            PartnerAuthorization.Denied ->
                listOf(R.string.partner_action_allow to Request.Allow(app.packageName))
            PartnerAuthorization.Pending, PartnerAuthorization.Undecided -> listOf(
                R.string.partner_action_allow to Request.Allow(app.packageName),
                R.string.partner_action_deny to Request.Deny(app.packageName),
            )
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(app.label)
            .setItems(actions.map { context.getString(it.first) }.toTypedArray()) { _, which ->
                requests.trySend(actions[which].second)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.emptyIllustration.illustration = UndrawIllustration.Coder

        binding.recyclerList.applyLinearAdapter(context, adapter)
    }
}
