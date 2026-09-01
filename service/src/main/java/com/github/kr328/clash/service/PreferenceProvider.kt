package com.github.kr328.clash.service

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.common.constants.Authorities
import rikka.preference.MultiProcessPreference
import rikka.preference.PreferenceProvider

class PreferenceProvider : PreferenceProvider() {
    override fun onCreatePreference(context: Context): SharedPreferences {
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val FILE_NAME = "service"

        fun createSharedPreferencesFromContext(context: Context): SharedPreferences {
            val application = context.applicationContext as Application

            // Prefer direct SharedPreferences when running inside the :background process
            // that hosts this provider; every other process (the UI process) must go
            // through the cross-process ContentProvider. Deciding by process name rather
            // than by context type keeps this correct as more components are added to the
            // same process (B-192).
            return if (application.currentProcessName == "${application.packageName}:background") {
                application.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            } else {
                MultiProcessPreference(
                    application,
                    Authorities.SETTINGS_PROVIDER
                )
            }
        }
    }
}