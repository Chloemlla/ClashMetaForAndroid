package com.github.kr328.clash.service.data.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE imported ADD COLUMN ageSecretKey TEXT")
        database.execSQL("ALTER TABLE pending ADD COLUMN ageSecretKey TEXT")
    }
}

// The selections foreign key previously declared ON UPDATE CASCADE, which never fired
// (uuid is the immutable primary key) and contradicted the twin tables' ABORT conflict
// semantics. Recreate the table with the default ON UPDATE NO ACTION.
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `selections_new` (" +
                "`uuid` TEXT NOT NULL, " +
                "`proxy` TEXT NOT NULL, " +
                "`selected` TEXT NOT NULL, " +
                "PRIMARY KEY(`uuid`, `proxy`), " +
                "FOREIGN KEY(`uuid`) REFERENCES `imported`(`uuid`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        // Rows orphaned before the foreign key existed would abort the copy; ON DELETE CASCADE
        // means they were already meant to be gone.
        database.execSQL(
            "INSERT INTO `selections_new` (`uuid`, `proxy`, `selected`) " +
                "SELECT `uuid`, `proxy`, `selected` FROM `selections` " +
                "WHERE `uuid` IN (SELECT `uuid` FROM `imported`)"
        )
        database.execSQL("DROP TABLE `selections`")
        database.execSQL("ALTER TABLE `selections_new` RENAME TO `selections`")
    }
}

val MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
)

val LEGACY_MIGRATION = ::migrationFromLegacy
