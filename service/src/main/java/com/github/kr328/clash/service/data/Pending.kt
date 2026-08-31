package com.github.kr328.clash.service.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.TypeConverters
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.UUIDSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.*

@Entity(tableName = "pending", primaryKeys = ["uuid"])
@TypeConverters(Converters::class)
@Serializable
data class Pending(
    @SerialName("uuid")
    @Serializable(with = UUIDSerializer::class)
    @ColumnInfo(name = "uuid") val uuid: UUID,
    @SerialName("name")
    @ColumnInfo(name = "name") val name: String,
    @SerialName("type")
    @ColumnInfo(name = "type") val type: Profile.Type,
    @SerialName("source")
    @ColumnInfo(name = "source") val source: String = "",
    @SerialName("interval")
    @ColumnInfo(name = "interval") val interval: Long = 0,
    @SerialName("upload")
    @ColumnInfo(name = "upload") val upload: Long = 0,
    @SerialName("download")
    @ColumnInfo(name = "download") val download: Long = 0,
    @SerialName("total")
    @ColumnInfo(name = "total") val total: Long = 0,
    @SerialName("expire")
    @ColumnInfo(name = "expire") val expire: Long = 0,
    @SerialName("createdAt")
    @ColumnInfo(name = "createdAt") val createdAt: Long = System.currentTimeMillis(),
    @SerialName("ageSecretKey")
    @ColumnInfo(name = "ageSecretKey") val ageSecretKey: String? = null,
)
