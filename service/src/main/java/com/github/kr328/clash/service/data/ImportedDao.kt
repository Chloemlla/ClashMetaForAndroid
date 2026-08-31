package com.github.kr328.clash.service.data

import androidx.room.*
import java.util.*

@Dao
@TypeConverters(Converters::class)
interface ImportedDao {
    @Query("SELECT * FROM imported WHERE uuid = :uuid")
    suspend fun queryByUUID(uuid: UUID): Imported?

    @Query("SELECT uuid FROM imported ORDER BY createdAt")
    suspend fun rawQueryAllUUIDs(): List<UUID>

    @Query("SELECT * FROM imported ORDER BY createdAt")
    suspend fun rawQueryAll(): List<Imported>

    // A corrupt uuid string converts to the sentinel; drop those rows so one bad row
    // cannot surface as a nil-uuid profile and break the whole profile list.
    suspend fun queryAllUUIDs(): List<UUID> = rawQueryAllUUIDs().filter { it != Converters.INVALID_UUID }

    suspend fun queryAll(): List<Imported> = rawQueryAll().filter { it.uuid != Converters.INVALID_UUID }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(imported: Imported)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(imported: Imported)

    @Query("DELETE FROM imported WHERE uuid = :uuid")
    suspend fun remove(uuid: UUID)

    @Query("SELECT EXISTS(SELECT 1 FROM imported WHERE uuid = :uuid)")
    suspend fun exists(uuid: UUID): Boolean
}
