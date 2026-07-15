package com.github.kr328.clash.common.store

import android.content.SharedPreferences
import androidx.core.content.edit

class SharedPreferenceProvider(private val preferences: SharedPreferences) : StoreProvider {
    override fun getInt(key: String, defaultValue: Int): Int {
        return preferences.getInt(key, defaultValue)
    }

    override fun setInt(key: String, value: Int) {
        preferences.edit {
            putInt(key, value)
        }
    }

    override fun getLong(key: String, defaultValue: Long): Long {
        return preferences.getLong(key, defaultValue)
    }

    override fun setLong(key: String, value: Long) {
        preferences.edit {
            putLong(key, value)
        }
    }

    override fun getString(key: String, defaultValue: String): String {
        // Fall back to the default rather than force-unwrapping: a key persisted under a
        // mismatched type (e.g. after a migration merge) can make getString return null or
        // throw ClassCastException, which !! would turn into a crash.
        return try {
            preferences.getString(key, defaultValue) ?: defaultValue
        } catch (_: ClassCastException) {
            defaultValue
        }
    }

    override fun setString(key: String, value: String) {
        preferences.edit {
            putString(key, value)
        }
    }

    override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> {
        // See getString: avoid !! so a type-mismatched persisted key degrades to the
        // default instead of crashing the reader.
        return try {
            preferences.getStringSet(key, defaultValue) ?: defaultValue
        } catch (_: ClassCastException) {
            defaultValue
        }
    }

    override fun setStringSet(key: String, value: Set<String>) {
        preferences.edit {
            putStringSet(key, value)
        }
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return preferences.getBoolean(key, defaultValue)
    }

    override fun setBoolean(key: String, value: Boolean) {
        preferences.edit {
            putBoolean(key, value)
        }
    }
}

fun SharedPreferences.asStoreProvider(): StoreProvider {
    return SharedPreferenceProvider(this)
}