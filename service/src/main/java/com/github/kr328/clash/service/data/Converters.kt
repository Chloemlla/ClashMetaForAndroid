package com.github.kr328.clash.service.data

import androidx.room.TypeConverter
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.SecureStorage
import java.util.*

class Converters {
    @TypeConverter
    fun fromUUID(uuid: UUID): String {
        return uuid.toString()
    }

    @TypeConverter
    fun toUUID(uuid: String): UUID {
        return UUID.fromString(uuid)
    }

    @TypeConverter
    fun fromProfileType(type: Profile.Type): String {
        return type.name
    }

    @TypeConverter
    fun toProfileType(type: String): Profile.Type {
        return Profile.Type.valueOf(type)
    }

    /**
     * Encrypt [ageSecretKey] before storing in Room.
     * Uses Android Keystore-backed AES-GCM via [SecureStorage].
     */
    @TypeConverter
    fun fromSecureString(value: String?): String? {
        if (value == null || value.isEmpty()) return null
        return try {
            SecureStorage.encrypt(value)
        } catch (e: Exception) {
            // If encryption fails, store as-is (fallback for Keystore unavailability).
            value
        }
    }

    /**
     * Decrypt [ageSecretKey] when reading from Room.
     * Plaintext values (legacy data or fallback) are returned as-is.
     */
    @TypeConverter
    fun toSecureString(value: String?): String? {
        if (value == null || value.isEmpty()) return null
        return try {
            SecureStorage.decrypt(value)
        } catch (e: Exception) {
            // Not encrypted or decryption failed — return as-is (legacy plaintext).
            value
        }
    }
}