package com.github.kr328.clash

import com.chloemlla.lumen.crash.CrashBreadcrumbs
import com.chloemlla.lumen.crash.LumenCrash
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.ProfilesDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile as ServiceProfile
import com.github.kr328.clash.service.util.SubscriptionExpiryNotifier
import com.github.kr328.clash.util.ProfileFileExport
import com.github.kr328.clash.util.ProfileQrExport
import com.github.kr328.clash.util.toDesignProfile
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

class ProfilesActivity : BaseActivity<ProfilesDesign>() {
    override suspend fun main() {
        val design = ProfilesDesign(this)

        setContentDesign(design)

        val ticker = ticker(TimeUnit.MINUTES.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        // ServiceRecreated covers batch restore (SettingsActivity) which broadcasts
                        // one coarse event instead of a per-profile storm (B-85).
                        Event.ActivityStart, Event.ProfileChanged, Event.ServiceRecreated -> {
                            design.fetch()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProfilesDesign.Request.Create ->
                            startActivity(NewProfileActivity::class.intent)
                        ProfilesDesign.Request.UpdateAll -> {
                            try {
                                recordBreadcrumbSafe("Profiles update-all requested")
                                withProfile {
                                    queryAll().forEach { p ->
                                        if (p.imported && p.type != ServiceProfile.Type.File)
                                            update(p.uuid)
                                    }
                                }
                            } finally {
                                withContext(Dispatchers.Main) {
                                    design.finishUpdateAll()
                                }
                            }
                        }
                        is ProfilesDesign.Request.Update ->
                            withProfile {
                                recordBreadcrumbSafe("Profile update requested name=${it.profile.name}")
                                update(it.profile.uuid)
                            }
                        is ProfilesDesign.Request.Delete ->
                            withProfile { delete(it.profile.uuid) }
                        is ProfilesDesign.Request.Edit ->
                            startActivity(PropertiesActivity::class.intent.setUUID(it.profile.uuid))
                        is ProfilesDesign.Request.Active -> {
                            withProfile {
                                if (it.profile.imported)
                                    queryByUUID(it.profile.uuid)?.let { profile ->
                                        setActive(profile)
                                    }
                                else
                                    design.requestSave(it.profile)
                            }
                        }
                        is ProfilesDesign.Request.Duplicate -> {
                            val uuid = withProfile { clone(it.profile.uuid) }

                            startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                        }
                        is ProfilesDesign.Request.ExportQr -> {
                            ProfileQrExport.show(design, it.profile)
                        }
                        is ProfilesDesign.Request.ExportFile -> {
                            ProfileFileExport.share(design, it.profile)
                        }
                        is ProfilesDesign.Request.ResetLocalTraffic -> {
                            withProfile {
                                resetLocalTraffic(it.profile.uuid)
                            }
                            design.showToast(R.string.reset_local_traffic_done, ToastDuration.Short)
                            design.fetch()
                        }
                    }
                }
                if (activityStarted) {
                    ticker.onReceive {
                        design.updateElapsed()
                    }
                }
            }
        }
    }

    private suspend fun ProfilesDesign.fetch() {
        withProfile {
            patchProfiles(queryAll().map { it.toDesignProfile() })
        }
        launch {
            runCatching {
                SubscriptionExpiryNotifier.checkAll(this@ProfilesActivity)
            }
        }
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        if (uuid == null) return
        launch {
            val name = queryProfileName(uuid)
            recordBreadcrumbSafe("Profile update completed name=$name")
            design?.showToast(
                getString(R.string.toast_profile_updated_complete, name),
                ToastDuration.Long
            )
        }
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if (uuid == null) return
        launch {
            val name = queryProfileName(uuid)
            recordBreadcrumbSafe("Profile update failed name=$name reason=${reason ?: "unknown"}")
            design?.showToast(
                getString(R.string.toast_profile_updated_failed, name, reason),
                ToastDuration.Long
            ) {
                setAction(R.string.edit) {
                    startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                }
            }
        }
    }

    private suspend fun queryProfileName(uuid: UUID): String? =
        withProfile { queryByUUID(uuid)?.name }

    private fun recordBreadcrumbSafe(event: String) {
        if (!LumenCrash.isInstalled()) return
        runCatching { CrashBreadcrumbs.record(event) }
    }
}
