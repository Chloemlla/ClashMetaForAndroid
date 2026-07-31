package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.design.PropertiesDesign
import com.github.kr328.clash.design.model.Profile
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.toDesignProfile
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.util.ProfileQrExport
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import com.github.kr328.clash.design.R

class PropertiesActivity : BaseActivity<PropertiesDesign>() {
    private var canceled: Boolean = false
    private lateinit var original: Profile
    private lateinit var serviceStore: ServiceStore
    private var originalLocalTrafficBilling: Boolean = true

    override suspend fun main() {
        setResult(RESULT_CANCELED)

        val uuid = intent.uuid ?: return finish()
        val design = PropertiesDesign(this)
        serviceStore = ServiceStore(this)

        original = withProfile { queryByUUID(uuid) }?.toDesignProfile() ?: return finish()

        originalLocalTrafficBilling = serviceStore.getLocalSubscriptionTraffic(uuid)
        design.profile = original
        design.localTrafficBilling = originalLocalTrafficBilling

        setContentDesign(design)

        defer {
            canceled = true

            withProfile { release(uuid) }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStop -> {
                            val profile = design.profile

                            if (!canceled && profile != original) {
                                withProfile {
                                    patch(profile.uuid, profile.name, profile.source, profile.interval, profile.ageSecretKey)
                                }
                            }
                        }
                        Event.ServiceRecreated -> {
                            finish()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        PropertiesDesign.Request.BrowseFiles -> {
                            startActivity(FilesActivity::class.intent.setUUID(uuid))
                        }
                        PropertiesDesign.Request.Commit -> {
                            design.verifyAndCommit()
                        }
                        PropertiesDesign.Request.ExportQr -> {
                            ProfileQrExport.show(design, design.profile)
                        }
                    }
                }
            }
        }
    }

    override fun onBackPressedCompat(): Boolean {
        val current = design ?: return false
        current.launch {
            if (!current.progressing) {
                val unchanged = original == current.profile &&
                    originalLocalTrafficBilling == current.localTrafficBilling
                if (unchanged || current.requestExitWithoutSaving()) {
                    finish()
                }
            }
        }
        return true
    }

    private suspend fun PropertiesDesign.verifyAndCommit() {
        when {
            profile.name.isBlank() -> {
                showToast(R.string.empty_name, ToastDuration.Long)
            }
            profile.type != Profile.Type.File && profile.source.isBlank() -> {
                showToast(R.string.invalid_url, ToastDuration.Long)
            }
            else -> {
                val previousBilling = originalLocalTrafficBilling
                try {
                    withProcessing { updateStatus ->
                        withProfile {
                            // Persist billing mode before apply so import uses the selected strategy.
                            serviceStore.setLocalSubscriptionTraffic(profile.uuid, localTrafficBilling)

                            patch(profile.uuid, profile.name, profile.source, profile.interval, profile.ageSecretKey)

                            coroutineScope {
                                commit(profile.uuid) {
                                    launch {
                                        updateStatus(it)
                                    }
                                }
                            }
                        }
                    }

                    originalLocalTrafficBilling = localTrafficBilling
                    setResult(RESULT_OK)

                    finish()
                } catch (e: Exception) {
                    serviceStore.setLocalSubscriptionTraffic(profile.uuid, previousBilling)
                    showExceptionToast(e)
                }
            }
        }
    }
}
