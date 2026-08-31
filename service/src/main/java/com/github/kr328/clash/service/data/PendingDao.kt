package com.github.kr328.clash.service.data

import androidx.room.*
import java.util.*

@Dao
@TypeConverters(Converters::class)
interface PendingDao {
    @Query("SELECT * FROM pending WHERE uuid = :uuid")
    suspend fun queryByUUID(uuid: UUID): Pending?

    @Query("DELETE FROM pending WHERE uuid = :uuid")
    suspend fun remove(uuid: UUID)

    @Query("SELECT EXISTS(SELECT 1 FROM pending WHERE uuid = :uuid)")
    suspend fun exists(uuid: UUID): Boolean

    @Query("SELECT uuid FROM pending ORDER BY createdAt")
    suspend fun rawQueryAllUUIDs(): List<UUID>

    @Query("SELECT * FROM pending ORDER BY createdAt")
    suspend fun rawQueryAll(): List<Pending>

    // A corrupt uuid string converts to the sentinel; drop those rows so one bad row
    // cannot surface as a nil-uuid profile and break the whole profile list.
    suspend fun queryAllUUIDs(): List<UUID> = rawQueryAllUUIDs().filter { it != Converters.INVALID_UUID }

    suspend fun queryAll(): List<Pending> = rawQueryAll().filter { it.uuid != Converters.INVALID_UUID }

    // UUID keys are generated app-side, so a conflict is a corrupt write; fail loudly
    // instead of silently overwriting an existing profile. Both twin tables agree on
    // this. Import paths pre-filter duplicates with exists() before inserting.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(pending: Pending)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(pending: Pending)
}
