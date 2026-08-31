package com.github.kr328.clash

import com.github.kr328.clash.design.AutomationSettingsDesign
import com.github.kr328.clash.design.model.SceneProfileOption
import com.github.kr328.clash.store.AutomationSettingsAdapter
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class AutomationSettingsActivity : BaseActivity<AutomationSettingsDesign>() {
    override suspend fun main() {
        val settings = AutomationSettingsAdapter(this)

        // A-35: the adapter is local; only the profile list is a cross-process read. If that read
        // fails, keep a visible skeleton (settings with an empty profile picker) and let
        // BaseActivity show the error instead of a blank window.
        val profiles = try {
            withProfile {
                queryAll()
                    .filter { it.imported }
                    .map { SceneProfileOption(it.uuid.toString(), it.name) }
            }
        } catch (e: Exception) {
            setContentDesign(AutomationSettingsDesign(this, settings, emptyList()))
            throw e
        }
        val design = AutomationSettingsDesign(this, settings, profiles)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    if (it == Event.ProfileChanged) recreate()
                }
                design.requests.onReceive {
                    when (it) {
                        AutomationSettingsDesign.Request.AddTemplates -> {
                            settings.addMissingTemplates()
                            recreate()
                        }
                        is AutomationSettingsDesign.Request.MoveScene -> {
                            settings.moveScene(it.id, it.offset)
                            recreate()
                        }
                    }
                }
            }
        }
    }
}
