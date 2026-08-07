package com.github.kr328.clash

import android.content.pm.PackageManager
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.common.constants.Metadata
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.design.OverrideSettingsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.elapsedIntervalString
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class OverrideSettingsActivity : BaseActivity<OverrideSettingsDesign>() {
    override suspend fun main() {
        val configuration = withClash { queryOverride(Clash.OverrideSlot.Persist) }
        val service = ServiceStore(this)

        defer {
            withClash {
                patchOverride(Clash.OverrideSlot.Persist, configuration)
            }
        }

        val design = OverrideSettingsDesign(
            this,
            configuration
        )

        setContentDesign(design)

        suspend fun refreshAdblockStatus() {
            val enabled = configuration.app.adblock != false

            val adblock = try {
                withClash { queryProviders() }.firstOrNull {
                    it.name == ADBLOCK_PROVIDER_NAME && it.type == Provider.Type.Rule
                }
            } catch (e: Exception) {
                null
            }

            val summary = when {
                !enabled -> getString(R.string.adblock_rules_status_disabled)
                adblock == null -> getString(R.string.adblock_rules_status_missing)
                adblock.updatedAt <= 0L -> getString(R.string.adblock_rules_status_missing)
                else -> {
                    val ago = (System.currentTimeMillis() - adblock.updatedAt)
                        .elapsedIntervalString(this)
                    getString(R.string.adblock_rules_status_updated, ago)
                }
            }

            design.setAdblockEnabled(enabled)
            design.setAdblockStatus(summary)
        }

        refreshAdblockStatus()

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ProfileLoaded -> refreshAdblockStatus()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        OverrideSettingsDesign.Request.ResetOverride -> {
                            if (design.requestResetConfirm()) {
                                defer {
                                    withClash {
                                        clearOverride(Clash.OverrideSlot.Persist)
                                    }
                                }

                                finish()
                            }
                        }
                        OverrideSettingsDesign.Request.UpdateAdblock -> {
                            design.setAdblockStatus(getString(R.string.adblock_rules_status_updating))

                            launch {
                                try {
                                    withClash { updateAdblock() }
                                } catch (e: Exception) {
                                    design.showExceptionToast(
                                        getString(
                                            R.string.format_update_provider_failure,
                                            ADBLOCK_PROVIDER_NAME,
                                            e.message
                                        )
                                    )
                                }

                                refreshAdblockStatus()
                            }
                        }
                        OverrideSettingsDesign.Request.ShowAdblockUrl -> {
                            design.requestAdblockUrl(ADBLOCK_PROVIDER_URL)
                        }
                        OverrideSettingsDesign.Request.OpenAdblockHits -> {
                            startActivity(AdblockHitsActivity::class.intent)
                        }
                    }
                }
                if (activityStarted) {
                    onTimeout(ADBLOCK_REFRESH_INTERVAL_MS) {
                        refreshAdblockStatus()
                    }
                }
            }
        }
    }

    private companion object {
        const val ADBLOCK_PROVIDER_NAME = "cfm-adblock"
        const val ADBLOCK_PROVIDER_URL =
            "https://raw.githubusercontent.com/217heidai/adblockfilters/main/rules/adblockmihomo.mrs"
        const val ADBLOCK_REFRESH_INTERVAL_MS = 10_000L
    }
}
