package com.github.kr328.clash

import android.content.Intent
import com.github.kr328.clash.design.OpenSourceNoticeDesign
import com.github.kr328.clash.store.AppStore
import kotlinx.coroutines.isActive

class OpenSourceNoticeActivity : BaseActivity<OpenSourceNoticeDesign>() {
    override suspend fun main() {
        val design = OpenSourceNoticeDesign(this) { uri ->
            startActivity(Intent(Intent.ACTION_VIEW).setData(uri))
        }

        setContentDesign(design)

        while (isActive) {
            when (design.requests.receive()) {
                OpenSourceNoticeDesign.Request.Accept -> {
                    val store = AppStore(this)
                    store.openSourceNoticeAccepted = true
                    // Seed gate so first install does not immediately show update notes.
                    store.lastSeenBuildIdentity = "${BuildConfig.VERSION_CODE}|${BuildConfig.COMMIT_HASH}"
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    override fun onBackPressedCompat(): Boolean {
        // First-launch notice is a hard gate: back just leaves the app.
        finishAffinity()
        return true
    }
}
