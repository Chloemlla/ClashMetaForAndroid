package com.github.kr328.clash.design

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenProxy,
        OpenProfiles,
        OpenProviders,
        OpenLogs,
        OpenSettings,
        OpenHelp,
        OpenAbout,
        CreateProfile,
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
            binding.hasProfile = !name.isNullOrBlank()
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.clashRunning = running
            if (running) {
                binding.clashStarting = false
            } else {
                binding.statusSubtext = context.getString(R.string.tap_to_start)
                binding.proxySummary = ""
            }
            refreshStatusAccessibility()
        }
    }

    suspend fun setClashStarting(starting: Boolean) {
        withContext(Dispatchers.Main) {
            binding.clashStarting = starting
            if (starting) {
                binding.statusSubtext = context.getString(R.string.starting_service)
            }
            refreshStatusAccessibility()
        }
    }

    suspend fun setTrafficSummary(total: Long, now: Long = 0L) {
        withContext(Dispatchers.Main) {
            binding.statusSubtext = context.getString(
                R.string.format_traffic_forwarded_with_speed,
                total.trafficTotal(),
                now.trafficUpload(),
                now.trafficDownload(),
            )
            refreshStatusAccessibility()
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            val modeText = modeLabel(mode)
            binding.mode = modeText
            if (binding.proxySummary.isNullOrBlank()) {
                binding.proxySummary = modeText
            }
        }
    }

    suspend fun setProxySummary(mode: TunnelState.Mode, selectedNode: String?) {
        withContext(Dispatchers.Main) {
            val modeText = modeLabel(mode)
            binding.mode = modeText
            binding.proxySummary = if (selectedNode.isNullOrBlank()) {
                modeText
            } else {
                context.getString(R.string.format_proxy_summary, modeText, selectedNode)
            }
        }
    }

    suspend fun setHasProviders(has: Boolean) {
        withContext(Dispatchers.Main) {
            binding.hasProviders = has
        }
    }

    suspend fun showAbout(versionName: String) {
        withContext(Dispatchers.Main) {
            val repositoryUrl = context.getString(R.string.meta_github_url)
            val binding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
                this.repositoryUrl = repositoryUrl
            }

            val openRepository = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(repositoryUrl))
                )
            }

            binding.repositoryUrl.setOnClickListener { openRepository() }

            AlertDialog.Builder(context)
                .setView(binding.root)
                .setPositiveButton(R.string.open_source_repository) { _, _ ->
                    openRepository()
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        }
    }

    init {
        binding.self = this
        binding.hasProfile = false
        binding.clashStarting = false
        binding.statusSubtext = context.getString(R.string.tap_to_start)
        binding.proxySummary = ""

        binding.colorClashStarted = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        binding.colorClashStopped = context.resolveThemedColor(R.attr.colorClashStopped)
        refreshStatusAccessibility()
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    private fun modeLabel(mode: TunnelState.Mode): String {
        return when (mode) {
            TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
            TunnelState.Mode.Global -> context.getString(R.string.global_mode)
            TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
            else -> context.getString(R.string.rule_mode)
        }
    }

    private fun refreshStatusAccessibility() {
        val description = when {
            binding.clashStarting == true -> context.getString(R.string.a11y_status_starting)
            binding.clashRunning == true -> context.getString(
                R.string.a11y_status_running,
                binding.statusSubtext.orEmpty(),
            )
            else -> context.getString(R.string.a11y_status_stopped)
        }
        binding.statusCard.contentDescription = description
    }
}