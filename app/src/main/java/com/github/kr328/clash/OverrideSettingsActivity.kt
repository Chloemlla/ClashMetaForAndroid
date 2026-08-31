package com.github.kr328.clash

import android.widget.Toast
import com.github.kr328.clash.common.constants.Adblock
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.design.OverrideSettingsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.elapsedIntervalString
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

class OverrideSettingsActivity : BaseActivity<OverrideSettingsDesign>() {
    override suspend fun main() {
        // A-35: a config-editing page must not show editable defaults on failure — saving them
        // would overwrite the real override with blanks. Instead surface the error and close.
        val configuration = try {
            withClash { queryOverride(Clash.OverrideSlot.Persist) }
        } catch (e: Exception) {
            runCatching {
                Toast.makeText(this, R.string.failed_to_load_settings, Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }

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
                    it.name == Adblock.PROVIDER_NAME && it.type == Provider.Type.Rule
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
                                            Adblock.PROVIDER_NAME,
                                            e.message
                                        )
                                    )
                                }

                                refreshAdblockStatus()
                            }
                        }
                        OverrideSettingsDesign.Request.ShowAdblockUrl -> {
                            design.requestAdblockUrl(Adblock.PROVIDER_URL)
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
        const val ADBLOCK_REFRESH_INTERVAL_MS = 10_000L
    }
}
