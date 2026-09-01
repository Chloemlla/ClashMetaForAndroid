package com.github.kr328.clash.service.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.service.data.migrations.LEGACY_MIGRATION
import com.github.kr328.clash.service.data.migrations.MIGRATIONS
import java.util.concurrent.atomic.AtomicBoolean
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

        private val legacyMigrationStarted = AtomicBoolean(false)

        private fun open(context: Context): Database {
            return Room.databaseBuilder(
                context.applicationContext,
                Database::class.java,
                "profiles"
            ).addMigrations(*MIGRATIONS).build()
        }

        init {
            // A-11: the legacy migration mutates the shared profile database and must run
            // exactly once, in the process that owns the data (:background). The previous
            // unconditional launch fired from whichever process first touched this class
            // (including the main process via MigrationProvider), racing the Room instance
            // and risking duplicate rows across processes. If the process name cannot be
            // resolved, keep the old behavior and run it rather than risk losing legacy data.
            val isBackgroundProcess = runCatching {
                Global.application.currentProcessName.endsWith(BACKGROUND_PROCESS_SUFFIX)
            }.getOrDefault(true)

            if (isBackgroundProcess) {
                startLegacyMigration()
            }
        }

        private fun startLegacyMigration() {
            if (!legacyMigrationStarted.compareAndSet(false, true)) return
            Global.launch(Dispatchers.IO) {
                LEGACY_MIGRATION(Global.application)
            }
        }

        private const val BACKGROUND_PROCESS_SUFFIX = ":background"
    }
}
