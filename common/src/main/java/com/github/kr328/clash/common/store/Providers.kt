package com.github.kr328.clash.common.store

import android.content.SharedPreferences
import androidx.core.content.edit

class SharedPreferenceProvider(private val preferences: SharedPreferences) : StoreProvider {
    override fun contains(key: String): Boolean {
        return preferences.contains(key)
    }

    override fun remove(key: String) {
        dirty = true
        preferences.edit {
            remove(key)
        }
    }

    /**
     * apply() (the default of [edit]) only schedules an async disk write, while an empty
     * commit() is a no-op, so a freshly spawned process can read stale values. Track whether
     * anything was written and, when it was, force a real synchronous write with a stable
     * marker key before returning — this makes the just-applied values durable for the next
     * process to read.
     */
    override fun flush() {
        if (!dirty) return
        preferences.edit()
            .putLong(FLUSH_MARKER_KEY, System.currentTimeMillis())
            .commit()
        dirty = false
    }

    override fun getInt(key: String, defaultValue: Int): Int {
        return preferences.getInt(key, defaultValue)
    }

    override fun setInt(key: String, value: Int) {
        dirty = true
        preferences.edit {
            putInt(key, value)
        }
    }

    override fun getLong(key: String, defaultValue: Long): Long {
        return preferences.getLong(key, defaultValue)
    }

    override fun setLong(key: String, value: Long) {
        dirty = true
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
        dirty = true
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
        dirty = true
        preferences.edit {
            putStringSet(key, value)
        }
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return preferences.getBoolean(key, defaultValue)
    }

    override fun setBoolean(key: String, value: Boolean) {
        dirty = true
        preferences.edit {
            putBoolean(key, value)
        }
    }

    private var dirty: Boolean = false

    private companion object {
        /**
         * Internal marker written only to force a synchronous commit in [flush]. Not part of the
         * public preference surface; ignored by all readers.
         */
        const val FLUSH_MARKER_KEY = "__clash_flush_marker__"
    }
}

fun SharedPreferences.asStoreProvider(): StoreProvider {
    return SharedPreferenceProvider(this)
}