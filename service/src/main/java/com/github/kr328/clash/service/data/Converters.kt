package com.github.kr328.clash.service.data

import androidx.room.TypeConverter
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.Profile
import java.util.*

class Converters {
    // Sentinel for a corrupt row: the converter must not throw, or one bad row would
    // blank every list query in the app. List DAO queries filter this sentinel out.
    companion object {
        val INVALID_UUID: UUID = UUID(0, 0)
    }

    @TypeConverter
    fun fromUUID(uuid: UUID): String {
        return uuid.toString()
    }

    @TypeConverter
    fun toUUID(uuid: String): UUID {
        return runCatching { UUID.fromString(uuid) }.getOrElse {
            Log.w("Invalid uuid stored in database: $uuid", it)
            INVALID_UUID
        }
    }

    @TypeConverter
    fun fromProfileType(type: Profile.Type): String {
        return type.name
    }

    @TypeConverter
    fun toProfileType(type: String): Profile.Type {
        return runCatching { Profile.Type.valueOf(type) }.getOrElse {
            Log.w("Invalid profile type stored in database: $type", it)
            Profile.Type.File
        }
    }

    }
