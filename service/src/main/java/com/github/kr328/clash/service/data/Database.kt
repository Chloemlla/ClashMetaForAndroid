package com.github.kr328.clash.service.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.service.data.migrations.LEGACY_MIGRATION
import com.github.kr328.clash.service.data.migrations.MIGRATIONS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.room.Database as DB

@DB(
    version = 3,
    entities = [Imported::class, Pending::class, Selection::class],
    exportSchema = true,
)
abstract class Database : RoomDatabase() {
    abstract fun openImportedDao(): ImportedDao
    abstract fun openPendingDao(): PendingDao
    abstract fun openSelectionProxyDao(): SelectionDao

    @Transaction
    open suspend fun importAll(
        imported: List<Imported>,
        pending: List<Pending>,
        selections: List<Selection>,
    ) {
        val importedDao = openImportedDao()
        val pendingDao = openPendingDao()
        val selectionDao = openSelectionProxyDao()
        imported.forEach { importedDao.insert(it) }
        pending.forEach { pendingDao.insert(it) }
        selections.forEach { selectionDao.setSelected(it) }
    }

    // Atomically consume the pending row and write the imported row; returns false and
    // touches nothing if the pending row was edited after the caller's snapshot.
    @Transaction
    open suspend fun commitImported(imported: Imported, pending: Pending): Boolean {
        val importedDao = openImportedDao()
        val pendingDao = openPendingDao()
        if (pendingDao.queryByUUID(pending.uuid) != pending) return false
        if (importedDao.queryByUUID(imported.uuid) != null) {
            importedDao.update(imported)
        } else {
            importedDao.insert(imported)
        }
        pendingDao.remove(pending.uuid)
        return true
    }

    companion object {
        val database: Database by lazy { open(Global.application) }

        private fun open(context: Context): Database {
            return Room.databaseBuilder(
                context.applicationContext,
                Database::class.java,
                "profiles"
            ).addMigrations(*MIGRATIONS).build()
        }

        init {
            Global.launch(Dispatchers.IO) {
                LEGACY_MIGRATION(Global.application)
            }
        }
    }
}
