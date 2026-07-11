package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

class ExternalControlActivity : Activity(), CoroutineScope by MainScope() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        if (intent.action != Intent.ACTION_VIEW) return finish()

        val uri = intent.data ?: return finish()
        val url = uri.getQueryParameter("url") ?: return finish()

        launch {
            val uuid = withProfile {
                val type = when (uri.getQueryParameter("type")?.lowercase(Locale.getDefault())) {
                    "url" -> Profile.Type.Url
                    "file" -> Profile.Type.File
                    else -> Profile.Type.Url
                }
                val name = uri.getQueryParameter("name") ?: getString(R.string.new_profile)

                val parsedInterval = uri.getQueryParameter("update-interval")?.toLongOrNull() ?: 0L
                val updateInterval = if (parsedInterval > 0) parsedInterval.coerceAtLeast(15L) else 0L
                val intervalMs = TimeUnit.MINUTES.toMillis(updateInterval)

                create(type, name).also {
                    patch(it, name, url, intervalMs, null)
                }
            }
            startActivity(PropertiesActivity::class.intent.setUUID(uuid))
            finish()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
