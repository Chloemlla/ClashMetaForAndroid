package com.github.kr328.clash

import com.github.kr328.clash.design.UpdateNotesDesign
import com.github.kr328.clash.design.model.UpdateBuildIdentity
import com.github.kr328.clash.design.model.UpdateNotesDocument
import com.github.kr328.clash.store.AppStore
import kotlinx.coroutines.isActive

/**
 * Immersive update notes gate: shown once when BuildConfig identity changes.
 */
class UpdateNotesActivity : BaseActivity<UpdateNotesDesign>() {
    override suspend fun main() {
        val identity = UpdateBuildIdentity(
            commitHash = BuildConfig.COMMIT_HASH,
            buildTime = BuildConfig.BUILD_TIME,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        )
        val document = UpdateNotesDocument.loadFromRaw(this, identity)
        val design = UpdateNotesDesign(this, document)

        setContentDesign(design)

        while (isActive) {
            when (design.requests.receive()) {
                UpdateNotesDesign.Request.Accept -> {
                    AppStore(this).lastSeenBuildIdentity = identity.gateKey()
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    override fun onBackPressedCompat(): Boolean {
        // Soft gate: user can leave the app; notes reappear until accepted.
        finishAffinity()
        return true
    }
}
