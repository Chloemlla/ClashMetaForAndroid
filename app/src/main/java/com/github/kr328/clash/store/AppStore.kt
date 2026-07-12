package com.github.kr328.clash.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider

class AppStore(context: Context) {
    private val store = Store(
        context
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .asStoreProvider()
    )

    var updatedAt: Long by store.long(
        key = "updated_at",
        defaultValue = -1,
    )

    var alphaMigrationToastPending: Boolean by store.boolean(
        key = "alpha_migration_toast_pending",
        defaultValue = false,
    )

    var alphaMigrationImportedCount: Int by store.int(
        key = "alpha_migration_imported_count",
        defaultValue = 0,
    )

    companion object {
        private const val FILE_NAME = "app"
    }
}